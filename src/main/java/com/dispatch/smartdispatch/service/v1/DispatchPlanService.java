package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.*;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.TravelMatrixSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.TravelMatrixSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DispatchPlanService {
    private final DispatchDaySnapshotRepository dayRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final TravelMatrixSnapshotRepository matrixRepository;
    private final ObjectMapper objectMapper;

    public DispatchPlanService(DispatchDaySnapshotRepository dayRepository,
                               ReferenceSnapshotRepository referenceRepository,
                               TravelMatrixSnapshotRepository matrixRepository,
                               ObjectMapper objectMapper) {
        this.dayRepository = dayRepository;
        this.referenceRepository = referenceRepository;
        this.matrixRepository = matrixRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PlanResponse generate(LocalDate date, PlanRequest request) {
        long startedAt = System.nanoTime();
        DispatchDaySnapshotEntity day = dayRepository.findById(date)
                .orElseThrow(() -> V1ApiException.conflict(
                        "DAY_NOT_LOADED", "Upload day input before generating a plan"));

        JsonNode reference = referenceRepository.findById((short) 1)
                .map(ReferenceSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "REFERENCE_NOT_LOADED", "Reference data has not been uploaded"));
        JsonNode matrix = matrixRepository.findById((short) 1)
                .map(TravelMatrixSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "TRAVEL_MATRIX_NOT_LOADED", "Upload the travel matrix before generating a plan"));

        MatrixView travel = new MatrixView(matrix);
        Map<String, JsonNode> workTypes = indexBy(reference.path("work_types"), "code");
        List<StaffState> staff = parseStaff(reference.path("staff"), day.getPayload(), date, day.getModelNow());
        List<JsonNode> orders = new ArrayList<>();
        day.getPayload().path("orders").forEach(orders::add);
        orders.sort(Comparator
                .comparing((JsonNode order) -> parseTime(order.path("slot").path("start")))
                .thenComparing(order -> order.path("latest_start").asText())
                .thenComparing(order -> order.path("order_id").asText()));

        List<UnassignedOrderDto> unassigned = new ArrayList<>();
        for (JsonNode order : orders) {
            String orderId = order.path("order_id").asText();
            JsonNode workType = workTypes.get(order.path("work_type_code").asText());
            if (workType == null) {
                unassigned.add(unassigned(orderId, "OTHER",
                        "No work type definition is available for this order", "ESCALATE"));
                continue;
            }

            List<StaffState> qualified = staff.stream()
                    .filter(candidate -> isQualified(candidate, order, workType))
                    .toList();
            List<StaffState> available = qualified.stream().filter(candidate -> !candidate.unavailable).toList();

            if (qualified.isEmpty()) {
                unassigned.add(unassigned(orderId, "NO_QUALIFIED_STAFF",
                        "No staff member satisfies the order profile, technology and height requirements",
                        "REASSIGN"));
                continue;
            }
            if (available.isEmpty()) {
                unassigned.add(unassigned(orderId, "STAFF_UNAVAILABLE",
                        "All qualified staff members are marked absent for this day", "REASSIGN"));
                continue;
            }

            Candidate best = null;
            boolean blockedBySlot = false;
            boolean blockedByShift = false;
            for (StaffState candidate : available) {
                Candidate evaluation = evaluate(candidate, order, workType, travel, day.getModelNow());
                blockedBySlot |= evaluation.slotBlocked;
                blockedByShift |= evaluation.shiftBlocked;
                if (evaluation.feasible && (best == null || evaluation.score < best.score)) {
                    best = evaluation;
                }
            }

            if (best == null) {
                if (blockedBySlot) {
                    unassigned.add(unassigned(orderId, "SLOT_UNREACHABLE",
                            "No qualified available staff member can reach the order within its allowed start window",
                            "RESCHEDULE"));
                } else if (blockedByShift) {
                    unassigned.add(unassigned(orderId, "NO_SHIFT_TIME",
                            "The visit and return to the service center do not fit inside any qualified staff shift",
                            "RESCHEDULE"));
                } else {
                    unassigned.add(unassigned(orderId, "OTHER",
                            "No feasible route insertion was found", "ESCALATE"));
                }
                continue;
            }
            best.staff.visits.add(best.visit);
        }

        List<RouteDto> routes = buildRoutes(staff, travel);
        int nextVersion = day.getPlanVersion() + 1;
        long computationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        PlanResponse response = new PlanResponse(
                date,
                nextVersion,
                day.getModelNow(),
                routes,
                unassigned,
                List.of(),
                List.of(),
                List.of(),
                computationMs
        );

        try {
            day.updatePlan(nextVersion, objectMapper.valueToTree(response), day.getModelNow());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Could not serialize generated plan", ex);
        }
        dayRepository.save(day);
        return response;
    }

    @Transactional(readOnly = true)
    public JsonNode getLatest(LocalDate date) {
        DispatchDaySnapshotEntity day = dayRepository.findById(date)
                .orElseThrow(() -> V1ApiException.conflict(
                        "DAY_NOT_LOADED", "Day input has not been uploaded"));
        if (day.getPlanPayload() == null) {
            throw V1ApiException.conflict("PLAN_NOT_GENERATED", "Generate a plan before requesting it");
        }
        return day.getPlanPayload();
    }

    private Candidate evaluate(StaffState staff, JsonNode order, JsonNode workType,
                               MatrixView matrix, OffsetDateTime modelNow) {
        String buildingId = order.path("building_id").asText();
        String previousLocation = staff.visits.isEmpty()
                ? staff.serviceCenterId
                : staff.visits.get(staff.visits.size() - 1).buildingId;
        OffsetDateTime previousEnd = staff.visits.isEmpty()
                ? max(staff.shiftStart, modelNow)
                : staff.visits.get(staff.visits.size() - 1).end;

        long inboundSec = matrix.duration(previousLocation, buildingId);
        long inboundMeters = matrix.distance(previousLocation, buildingId);
        OffsetDateTime arrival = previousEnd.plusSeconds(inboundSec);
        OffsetDateTime slotStart = parseTime(order.path("slot").path("start"));
        OffsetDateTime latestStart = parseTime(order.path("latest_start"));
        OffsetDateTime visitStart = max(arrival, slotStart);

        if (visitStart.isAfter(latestStart)) {
            return Candidate.blocked(staff, true, false);
        }

        int durationMin = workType.path("duration_min").asInt();
        OffsetDateTime visitEnd = visitStart.plusMinutes(durationMin);
        if (visitEnd.isAfter(parseTime(order.path("deadline")))) {
            return Candidate.blocked(staff, true, false);
        }
        long returnSec = matrix.duration(buildingId, staff.serviceCenterId);
        long returnMeters = matrix.distance(buildingId, staff.serviceCenterId);
        OffsetDateTime depotArrival = visitEnd.plusSeconds(returnSec);
        if (depotArrival.isAfter(staff.shiftEnd)) {
            return Candidate.blocked(staff, false, true);
        }

        Visit visit = new Visit(buildingId, order.path("order_id").asText(),
                arrival, visitStart, visitEnd, inboundSec, inboundMeters);
        long score = inboundSec + returnSec;
        return Candidate.feasible(staff, visit, score);
    }

    private boolean isQualified(StaffState staff, JsonNode order, JsonNode workType) {
        Set<String> requiredProfiles = values(workType.path("profiles"));
        boolean profileMatch = staff.profiles.stream().anyMatch(requiredProfiles::contains);
        boolean technologyMatch = staff.technologies.contains(order.path("technology").asText());
        boolean heightMatch = !order.path("requires_height").asBoolean() || staff.heightPermit;
        return profileMatch && technologyMatch && heightMatch;
    }

    private List<StaffState> parseStaff(JsonNode staffArray, JsonNode dayPayload,
                                        LocalDate date, OffsetDateTime modelNow) {
        Set<String> absent = values(dayPayload.path("absent_staff_ids"));
        List<StaffState> result = new ArrayList<>();
        staffArray.forEach(node -> {
            String id = node.path("staff_id").asText();
            OffsetDateTime shiftStart = OffsetDateTime.of(date,
                    java.time.LocalTime.parse(node.path("shift").path("start").asText()), modelNow.getOffset());
            OffsetDateTime shiftEnd = OffsetDateTime.of(date,
                    java.time.LocalTime.parse(node.path("shift").path("end").asText()), modelNow.getOffset());
            result.add(new StaffState(
                    id,
                    node.path("service_center_id").asText(),
                    values(node.path("profiles")),
                    values(node.path("technologies")),
                    node.path("height_permit").asBoolean(),
                    absent.contains(id),
                    shiftStart,
                    shiftEnd,
                    max(shiftStart, modelNow)
            ));
        });
        return result;
    }

    private List<RouteDto> buildRoutes(List<StaffState> staff, MatrixView matrix) {
        List<RouteDto> result = new ArrayList<>();
        for (StaffState state : staff) {
            if (state.visits.isEmpty()) continue;
            List<StopDto> stops = new ArrayList<>();
            int seq = 1;
            OffsetDateTime routeStart = state.routeStart;
            // DEPOT_START is at the service center and has no travel leg.
            stops.add(new StopDto(seq++, "DEPOT_START", state.serviceCenterId,
                    routeStart, routeStart, routeStart, null, 0L, 0L, "PLANNED"));

            String previousLocation = state.serviceCenterId;
            OffsetDateTime previousEnd = routeStart;
            for (Visit visit : state.visits) {
                long travelSec = matrix.duration(previousLocation, visit.buildingId);
                long distanceM = matrix.distance(previousLocation, visit.buildingId);
                OffsetDateTime arrival = previousEnd.plusSeconds(travelSec);
                stops.add(new StopDto(seq++, "VISIT", visit.buildingId,
                        arrival, visit.start, visit.end, visit.orderId,
                        travelSec, distanceM, "PLANNED"));
                previousLocation = visit.buildingId;
                previousEnd = visit.end;
            }

            long travelSec = matrix.duration(previousLocation, state.serviceCenterId);
            long distanceM = matrix.distance(previousLocation, state.serviceCenterId);
            OffsetDateTime depotArrival = previousEnd.plusSeconds(travelSec);
            stops.add(new StopDto(seq, "DEPOT_END", state.serviceCenterId,
                    depotArrival, depotArrival, depotArrival, null,
                    travelSec, distanceM, "PLANNED"));
            result.add(new RouteDto(state.staffId, state.serviceCenterId, "ACTIVE", stops));
        }
        return result;
    }

    private Map<String, JsonNode> indexBy(JsonNode array, String key) {
        Map<String, JsonNode> result = new HashMap<>();
        array.forEach(node -> result.put(node.path(key).asText(), node));
        return result;
    }

    private Set<String> values(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null && array.isArray()) array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private OffsetDateTime parseTime(JsonNode node) {
        return OffsetDateTime.parse(node.asText());
    }

    private OffsetDateTime max(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private UnassignedOrderDto unassigned(String id, String code, String text, String action) {
        return new UnassignedOrderDto(id, code, text, action);
    }

    private static final class StaffState {
        private final String staffId;
        private final String serviceCenterId;
        private final Set<String> profiles;
        private final Set<String> technologies;
        private final boolean heightPermit;
        private final boolean unavailable;
        private final OffsetDateTime shiftStart;
        private final OffsetDateTime shiftEnd;
        private final OffsetDateTime routeStart;
        private final List<Visit> visits = new ArrayList<>();

        private StaffState(String staffId, String serviceCenterId, Set<String> profiles,
                           Set<String> technologies, boolean heightPermit, boolean unavailable,
                           OffsetDateTime shiftStart, OffsetDateTime shiftEnd,
                           OffsetDateTime routeStart) {
            this.staffId = staffId;
            this.serviceCenterId = serviceCenterId;
            this.profiles = profiles;
            this.technologies = technologies;
            this.heightPermit = heightPermit;
            this.unavailable = unavailable;
            this.shiftStart = shiftStart;
            this.shiftEnd = shiftEnd;
            this.routeStart = routeStart;
        }
    }

    private record Visit(String buildingId, String orderId, OffsetDateTime arrival,
                         OffsetDateTime start, OffsetDateTime end,
                         long travelSec, long distanceM) { }

    private static final class Candidate {
        private final StaffState staff;
        private final Visit visit;
        private final long score;
        private final boolean feasible;
        private final boolean slotBlocked;
        private final boolean shiftBlocked;

        private Candidate(StaffState staff, Visit visit, long score, boolean feasible,
                          boolean slotBlocked, boolean shiftBlocked) {
            this.staff = staff;
            this.visit = visit;
            this.score = score;
            this.feasible = feasible;
            this.slotBlocked = slotBlocked;
            this.shiftBlocked = shiftBlocked;
        }

        private static Candidate feasible(StaffState staff, Visit visit, long score) {
            return new Candidate(staff, visit, score, true, false, false);
        }

        private static Candidate blocked(StaffState staff, boolean slot, boolean shift) {
            return new Candidate(staff, null, Long.MAX_VALUE, false, slot, shift);
        }
    }

    private static final class MatrixView {
        private final Map<String, Integer> indexes = new HashMap<>();
        private final JsonNode durations;
        private final JsonNode distances;

        private MatrixView(JsonNode payload) {
            int index = 0;
            for (JsonNode point : payload.path("points")) {
                indexes.put(point.asText(), index++);
            }
            this.durations = payload.path("durations_sec");
            this.distances = payload.path("distances_m");
        }

        private long duration(String from, String to) {
            return value(durations, from, to);
        }

        private long distance(String from, String to) {
            return value(distances, from, to);
        }

        private long value(JsonNode matrix, String from, String to) {
            Integer row = indexes.get(from);
            Integer column = indexes.get(to);
            if (row == null || column == null || matrix.size() <= row
                    || matrix.path(row).size() <= column) {
                throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX",
                        "Travel matrix does not contain route point " + from + " -> " + to);
            }
            return matrix.path(row).path(column).asLong();
        }
    }
}
