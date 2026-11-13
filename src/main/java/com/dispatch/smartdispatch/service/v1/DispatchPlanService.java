package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.*;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.EquipmentDto;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DispatchPlanService {
    private static final String VISIT = "VISIT";
    private static final String RESTOCK = "RESTOCK";
    private static final String BREAK = "BREAK";

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
        JsonNode matrixPayload = matrixRepository.findById((short) 1)
                .map(TravelMatrixSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "TRAVEL_MATRIX_NOT_LOADED", "Upload the travel matrix before generating a plan"));

        MatrixView matrix = new MatrixView(matrixPayload);
        Map<String, JsonNode> workTypes = indexBy(reference.path("work_types"), "code");
        Map<String, ServiceCenter> centers = parseServiceCenters(reference.path("service_centers"));
        List<StaffState> staff = parseStaff(reference.path("staff"), day.getPayload(),
                centers, date, day.getModelNow());
        staff.sort(Comparator.comparing(candidate -> candidate.staffId));

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
            if (qualified.isEmpty()) {
                unassigned.add(unassigned(orderId, "NO_QUALIFIED_STAFF",
                        "No staff member satisfies the order profile, technology and height requirements",
                        "REASSIGN"));
                continue;
            }

            List<StaffState> available = qualified.stream()
                    .filter(candidate -> !candidate.unavailable)
                    .toList();
            if (available.isEmpty()) {
                unassigned.add(unassigned(orderId, "STAFF_UNAVAILABLE",
                        "All qualified staff members are marked absent for this day", "REASSIGN"));
                continue;
            }

            Candidate best = null;
            boolean anyCapacity = false;
            boolean slotBlocked = false;
            boolean shiftBlocked = false;
            boolean restockBlocked = false;
            for (StaffState candidate : available) {
                Equipment required = equipment(order.path("required_equipment"));
                anyCapacity |= candidate.equipmentCapacity.canCover(required);
                Candidate evaluated = evaluate(candidate, order, workType, centers, matrix);
                slotBlocked |= evaluated.slotBlocked;
                shiftBlocked |= evaluated.shiftBlocked;
                restockBlocked |= evaluated.restockBlocked;
                if (evaluated.feasible && (best == null || evaluated.score < best.score)) {
                    best = evaluated;
                }
            }

            if (best == null) {
                if (!anyCapacity || restockBlocked) {
                    unassigned.add(unassigned(orderId, "NO_EQUIPMENT",
                            "Required equipment is unavailable and no valid restock stop can be scheduled",
                            "RESCHEDULE"));
                } else if (slotBlocked) {
                    unassigned.add(unassigned(orderId, "SLOT_UNREACHABLE",
                            "No qualified available staff member can reach the order within its allowed start window",
                            "RESCHEDULE"));
                } else if (shiftBlocked) {
                    unassigned.add(unassigned(orderId, "NO_SHIFT_TIME",
                            "The visit, mandatory break and return to the service center do not fit inside a qualified staff shift",
                            "RESCHEDULE"));
                } else {
                    unassigned.add(unassigned(orderId, "OTHER",
                            "No feasible route insertion was found", "ESCALATE"));
                }
                continue;
            }

            best.staff.activities.clear();
            best.staff.activities.addAll(best.activities);
            best.staff.equipmentRemaining = best.schedule.finalEquipment;
        }

        List<RouteDto> routes = buildRoutes(staff, centers, matrix);
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
                               Map<String, ServiceCenter> centers, MatrixView matrix) {
        Equipment required = equipment(order.path("required_equipment"));
        List<Activity> base = new ArrayList<>(staff.activities);

        if (staff.equipmentRemaining.canCover(required)) {
            base.add(Activity.visit(order, workType));
            ScheduleSearch search = scheduleWithMandatoryBreak(staff, base, centers, matrix);
            if (search.result != null) {
                return Candidate.feasible(staff, base, search.result, score(staff, search.result, centers, matrix));
            }
            return Candidate.blocked(staff, search.slotBlocked, search.shiftBlocked,
                    search.restockBlocked);
        }

        if (!staff.equipmentCapacity.canCover(required)) {
            return Candidate.blocked(staff, false, false, true);
        }

        Candidate best = null;
        boolean slotBlocked = false;
        boolean shiftBlocked = false;
        boolean restockBlocked = false;
        for (ServiceCenter center : centers.values()) {
            List<Activity> withRestock = new ArrayList<>(staff.activities);
            withRestock.add(Activity.restock(center.serviceCenterId));
            withRestock.add(Activity.visit(order, workType));

            ScheduleSearch search = scheduleWithMandatoryBreak(staff, withRestock, centers, matrix);
            slotBlocked |= search.slotBlocked;
            shiftBlocked |= search.shiftBlocked;
            restockBlocked |= search.restockBlocked;

            if (search.result != null) {
                Candidate candidate = Candidate.feasible(staff, withRestock,
                        search.result, score(staff, search.result, centers, matrix));
                if (best == null || candidate.score < best.score) {
                    best = candidate;
                }
            }
        }

        return best != null ? best
                : Candidate.blocked(staff, slotBlocked, shiftBlocked, restockBlocked || centers.isEmpty());
    }

    private ScheduleSearch scheduleWithMandatoryBreak(StaffState staff, List<Activity> activities,
                                                       Map<String, ServiceCenter> centers,
                                                       MatrixView matrix) {
        ScheduleResult best = null;
        boolean slotBlocked = false;
        boolean shiftBlocked = false;
        boolean restockBlocked = false;

        // Try every insertion point. The BREAK is scheduled at the previous stop's location.
        for (int breakPosition = 0; breakPosition <= activities.size(); breakPosition++) {
            ScheduleAttempt attempt = scheduleAt(staff, activities, breakPosition, centers, matrix);
            slotBlocked |= attempt.failure == Failure.SLOT;
            shiftBlocked |= attempt.failure == Failure.SHIFT;
            restockBlocked |= attempt.failure == Failure.RESTOCK;
            if (attempt.result != null && (best == null
                    || attempt.result.depotArrival.isBefore(best.depotArrival)
                    || (attempt.result.depotArrival.equals(best.depotArrival)
                    && attempt.result.totalTravelSec < best.totalTravelSec))) {
                best = attempt.result;
            }
        }
        return new ScheduleSearch(best, slotBlocked, shiftBlocked, restockBlocked);
    }

    private ScheduleAttempt scheduleAt(StaffState staff, List<Activity> activities, int breakPosition,
                                       Map<String, ServiceCenter> centers, MatrixView matrix) {
        OffsetDateTime routeStart = staff.routeStart;
        if (routeStart.isAfter(staff.shiftEnd)) {
            return ScheduleAttempt.failed(Failure.SHIFT);
        }

        OffsetDateTime currentTime = routeStart;
        String currentLocation = staff.serviceCenterId;
        Equipment inventory = staff.equipmentStart;
        List<ScheduledActivity> scheduled = new ArrayList<>();
        long totalTravelSec = 0;
        long totalDistanceM = 0;

        for (int index = 0; index <= activities.size(); index++) {
            if (index == breakPosition) {
                OffsetDateTime arrival = currentTime;
                OffsetDateTime breakStart = max(currentTime, staff.breakWindowStart);
                if (breakStart.isAfter(staff.breakWindowEnd)) {
                    return ScheduleAttempt.failed(Failure.SHIFT);
                }
                OffsetDateTime breakEnd = breakStart.plusMinutes(staff.breakDurationMin);
                if (breakEnd.isAfter(staff.shiftEnd)) {
                    return ScheduleAttempt.failed(Failure.SHIFT);
                }
                scheduled.add(new ScheduledActivity(BREAK, currentLocation, null,
                        arrival, breakStart, breakEnd, 0L, 0L, inventory));
                currentTime = breakEnd;
            }

            if (index == activities.size()) {
                break;
            }

            Activity activity = activities.get(index);
            if (RESTOCK.equals(activity.type)) {
                ServiceCenter center = centers.get(activity.locationId);
                if (center == null) {
                    return ScheduleAttempt.failed(Failure.RESTOCK);
                }
                long travelSec = matrix.duration(currentLocation, center.serviceCenterId);
                long distanceM = matrix.distance(currentLocation, center.serviceCenterId);
                OffsetDateTime arrival = currentTime.plusSeconds(travelSec);
                OffsetDateTime open = at(staff.shiftStart.toLocalDate(), center.opening, staff.shiftStart);
                OffsetDateTime close = at(staff.shiftStart.toLocalDate(), center.closing, staff.shiftStart);
                OffsetDateTime start = max(arrival, open);
                OffsetDateTime end = start.plusMinutes(center.restockDurationMin);
                if (start.isAfter(close) || end.isAfter(close)) {
                    return ScheduleAttempt.failed(Failure.RESTOCK);
                }
                inventory = staff.equipmentCapacity;
                scheduled.add(new ScheduledActivity(RESTOCK, center.serviceCenterId, null,
                        arrival, start, end, travelSec, distanceM, inventory));
                currentLocation = center.serviceCenterId;
                currentTime = end;
                totalTravelSec += travelSec;
                totalDistanceM += distanceM;
                continue;
            }

            JsonNode order = activity.order;
            long travelSec = matrix.duration(currentLocation, activity.locationId);
            long distanceM = matrix.distance(currentLocation, activity.locationId);
            OffsetDateTime arrival = currentTime.plusSeconds(travelSec);
            OffsetDateTime slotStart = parseTime(order.path("slot").path("start"));
            OffsetDateTime latestStart = parseTime(order.path("latest_start"));
            OffsetDateTime start = max(arrival, slotStart);
            if (start.isAfter(latestStart)) {
                return ScheduleAttempt.failed(Failure.SLOT);
            }
            int durationMin = activity.workType.path("duration_min").asInt();
            OffsetDateTime end = start.plusMinutes(durationMin);
            if (end.isAfter(parseTime(order.path("deadline")))) {
                return ScheduleAttempt.failed(Failure.SLOT);
            }
            Equipment required = equipment(order.path("required_equipment"));
            if (!inventory.canCover(required)) {
                return ScheduleAttempt.failed(Failure.EQUIPMENT);
            }
            inventory = inventory.subtract(required);
            scheduled.add(new ScheduledActivity(VISIT, activity.locationId,
                    order.path("order_id").asText(), arrival, start, end,
                    travelSec, distanceM, inventory));
            currentLocation = activity.locationId;
            currentTime = end;
            totalTravelSec += travelSec;
            totalDistanceM += distanceM;
        }

        long returnSec = matrix.duration(currentLocation, staff.serviceCenterId);
        long returnMeters = matrix.distance(currentLocation, staff.serviceCenterId);
        OffsetDateTime depotArrival = currentTime.plusSeconds(returnSec);
        if (depotArrival.isAfter(staff.shiftEnd)) {
            return ScheduleAttempt.failed(Failure.SHIFT);
        }
        totalTravelSec += returnSec;
        totalDistanceM += returnMeters;

        return ScheduleAttempt.success(new ScheduleResult(
                routeStart, scheduled, depotArrival, returnSec, returnMeters,
                totalTravelSec, totalDistanceM, inventory
        ));
    }

    private List<RouteDto> buildRoutes(List<StaffState> staff,
                                       Map<String, ServiceCenter> centers,
                                       MatrixView matrix) {
        List<RouteDto> routes = new ArrayList<>();
        for (StaffState state : staff) {
            if (state.unavailable) {
                routes.add(new RouteDto(state.staffId, state.serviceCenterId, "UNAVAILABLE", List.of()));
                continue;
            }
            if (state.activities.isEmpty()) {
                continue;
            }

            ScheduleSearch search = scheduleWithMandatoryBreak(state, state.activities, centers, matrix);
            if (search.result == null) {
                throw new IllegalStateException("A previously accepted route is no longer feasible for staff "
                        + state.staffId);
            }

            ScheduleResult schedule = search.result;
            List<StopDto> stops = new ArrayList<>();
            int sequence = 1;
            stops.add(new StopDto(sequence++, "DEPOT_START", state.serviceCenterId,
                    schedule.routeStart, schedule.routeStart, schedule.routeStart,
                    null, 0L, 0L, "PLANNED", toDto(state.equipmentStart)));

            for (ScheduledActivity activity : schedule.activities) {
                stops.add(new StopDto(sequence++, activity.type, activity.locationId,
                        activity.arrival, activity.start, activity.end,
                        activity.orderId, activity.travelSec, activity.distanceM,
                        "PLANNED", toDto(activity.equipmentAfter)));
            }

            stops.add(new StopDto(sequence, "DEPOT_END", state.serviceCenterId,
                    schedule.depotArrival, schedule.depotArrival, schedule.depotArrival,
                    null, schedule.returnTravelSec, schedule.returnDistanceM,
                    "PLANNED", toDto(schedule.finalEquipment)));
            routes.add(new RouteDto(state.staffId, state.serviceCenterId, "ACTIVE", stops));
        }
        return routes;
    }

    private Map<String, ServiceCenter> parseServiceCenters(JsonNode array) {
        Map<String, ServiceCenter> result = new TreeMap<>();
        array.forEach(node -> {
            String id = node.path("service_center_id").asText();
            JsonNode hours = node.path("opening_hours");
            result.put(id, new ServiceCenter(
                    id,
                    LocalTime.parse(hours.path("open").asText()),
                    LocalTime.parse(hours.path("close").asText()),
                    node.path("restock_duration_min").asInt()
            ));
        });
        return result;
    }

    private List<StaffState> parseStaff(JsonNode staffArray, JsonNode dayPayload,
                                        Map<String, ServiceCenter> centers,
                                        LocalDate date, OffsetDateTime modelNow) {
        Set<String> absent = values(dayPayload.path("absent_staff_ids"));
        List<StaffState> result = new ArrayList<>();
        staffArray.forEach(node -> {
            String id = node.path("staff_id").asText();
            JsonNode shift = node.path("shift");
            JsonNode breakWindow = shift.path("break_window");
            OffsetDateTime shiftStart = at(date, LocalTime.parse(shift.path("start").asText()), modelNow);
            OffsetDateTime shiftEnd = at(date, LocalTime.parse(shift.path("end").asText()), modelNow);
            OffsetDateTime breakFrom = at(date, LocalTime.parse(breakWindow.path("from").asText()), modelNow);
            OffsetDateTime breakTo = at(date, LocalTime.parse(breakWindow.path("to").asText()), modelNow);
            String serviceCenterId = node.path("service_center_id").asText();
            if (!centers.containsKey(serviceCenterId)) {
                throw V1ApiException.unprocessable("INVALID_REFERENCE",
                        "Staff " + id + " references unknown service center " + serviceCenterId);
            }
            Equipment starting = equipment(node.path("equipment_start"));
            Equipment capacity = equipment(node.path("equipment_capacity"));
            result.add(new StaffState(
                    id,
                    serviceCenterId,
                    values(node.path("profiles")),
                    values(node.path("technologies")),
                    node.path("height_permit").asBoolean(),
                    absent.contains(id),
                    shiftStart,
                    shiftEnd,
                    max(shiftStart, modelNow),
                    shift.path("break_duration_min").asInt(),
                    breakFrom,
                    breakTo,
                    starting,
                    capacity
            ));
        });
        return result;
    }

    private boolean isQualified(StaffState staff, JsonNode order, JsonNode workType) {
        Set<String> requiredProfiles = values(workType.path("profiles"));
        boolean profileMatch = staff.profiles.stream().anyMatch(requiredProfiles::contains);
        boolean technologyMatch = staff.technologies.contains(order.path("technology").asText());
        boolean heightMatch = !order.path("requires_height").asBoolean() || staff.heightPermit;
        return profileMatch && technologyMatch && heightMatch;
    }

    private Map<String, JsonNode> indexBy(JsonNode array, String key) {
        Map<String, JsonNode> result = new HashMap<>();
        array.forEach(node -> result.put(node.path(key).asText(), node));
        return result;
    }

    private Set<String> values(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> result.add(value.asText()));
        }
        return result;
    }

    private Equipment equipment(JsonNode node) {
        return new Equipment(
                node.path("ONT").asInt(),
                node.path("ROUTER").asInt(),
                node.path("STB").asInt()
        );
    }

    private EquipmentDto toDto(Equipment equipment) {
        return new EquipmentDto(equipment.ont, equipment.router, equipment.stb);
    }

    private OffsetDateTime at(LocalDate date, LocalTime time, OffsetDateTime reference) {
        return OffsetDateTime.of(date, time, reference.getOffset());
    }

    private OffsetDateTime parseTime(JsonNode node) {
        return OffsetDateTime.parse(node.asText());
    }

    private OffsetDateTime max(OffsetDateTime a, OffsetDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private long score(StaffState staff, ScheduleResult candidate,
                       Map<String, ServiceCenter> centers, MatrixView matrix) {
        long baselineTravelSec = 0L;
        long baselineFinish = staff.routeStart.toEpochSecond();
        if (!staff.activities.isEmpty()) {
            ScheduleSearch baseline = scheduleWithMandatoryBreak(staff, staff.activities, centers, matrix);
            if (baseline.result != null) {
                baselineTravelSec = baseline.result.totalTravelSec;
                baselineFinish = baseline.result.depotArrival.toEpochSecond();
            }
        }
        long addedTravelSec = Math.max(0L, candidate.totalTravelSec - baselineTravelSec);
        long addedElapsedSec = Math.max(0L, candidate.depotArrival.toEpochSecond() - baselineFinish);
        // Prefer the smallest added travel; elapsed time is a deterministic tie-breaker.
        return addedTravelSec * 1_000_000L + Math.min(addedElapsedSec, 999_999L);
    }

    private UnassignedOrderDto unassigned(String id, String code, String text, String action) {
        return new UnassignedOrderDto(id, code, text, action);
    }

    private enum Failure { SLOT, SHIFT, RESTOCK, EQUIPMENT }

    private record ServiceCenter(String serviceCenterId, LocalTime opening,
                                 LocalTime closing, int restockDurationMin) { }

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
        private final int breakDurationMin;
        private final OffsetDateTime breakWindowStart;
        private final OffsetDateTime breakWindowEnd;
        private final Equipment equipmentStart;
        private final Equipment equipmentCapacity;
        private Equipment equipmentRemaining;
        private final List<Activity> activities = new ArrayList<>();

        private StaffState(String staffId, String serviceCenterId, Set<String> profiles,
                           Set<String> technologies, boolean heightPermit, boolean unavailable,
                           OffsetDateTime shiftStart, OffsetDateTime shiftEnd, OffsetDateTime routeStart,
                           int breakDurationMin, OffsetDateTime breakWindowStart,
                           OffsetDateTime breakWindowEnd, Equipment equipmentStart,
                           Equipment equipmentCapacity) {
            this.staffId = staffId;
            this.serviceCenterId = serviceCenterId;
            this.profiles = profiles;
            this.technologies = technologies;
            this.heightPermit = heightPermit;
            this.unavailable = unavailable;
            this.shiftStart = shiftStart;
            this.shiftEnd = shiftEnd;
            this.routeStart = routeStart;
            this.breakDurationMin = breakDurationMin;
            this.breakWindowStart = breakWindowStart;
            this.breakWindowEnd = breakWindowEnd;
            this.equipmentStart = equipmentStart;
            this.equipmentCapacity = equipmentCapacity;
            this.equipmentRemaining = equipmentStart;
        }
    }

    private record Equipment(int ont, int router, int stb) {
        private boolean canCover(Equipment required) {
            return ont >= required.ont && router >= required.router && stb >= required.stb;
        }

        private Equipment subtract(Equipment required) {
            return new Equipment(ont - required.ont, router - required.router, stb - required.stb);
        }
    }

    private record Activity(String type, String locationId, JsonNode order, JsonNode workType) {
        private static Activity visit(JsonNode order, JsonNode workType) {
            return new Activity(VISIT, order.path("building_id").asText(), order, workType);
        }

        private static Activity restock(String serviceCenterId) {
            return new Activity(RESTOCK, serviceCenterId, null, null);
        }
    }

    private record ScheduledActivity(String type, String locationId, String orderId,
                                     OffsetDateTime arrival, OffsetDateTime start, OffsetDateTime end,
                                     long travelSec, long distanceM, Equipment equipmentAfter) { }

    private record ScheduleResult(OffsetDateTime routeStart, List<ScheduledActivity> activities,
                                  OffsetDateTime depotArrival, long returnTravelSec, long returnDistanceM,
                                  long totalTravelSec, long totalDistanceM, Equipment finalEquipment) { }

    private record ScheduleSearch(ScheduleResult result, boolean slotBlocked,
                                  boolean shiftBlocked, boolean restockBlocked) { }

    private record ScheduleAttempt(ScheduleResult result, Failure failure) {
        private static ScheduleAttempt success(ScheduleResult result) {
            return new ScheduleAttempt(result, null);
        }

        private static ScheduleAttempt failed(Failure failure) {
            return new ScheduleAttempt(null, failure);
        }
    }

    private static final class Candidate {
        private final StaffState staff;
        private final List<Activity> activities;
        private final ScheduleResult schedule;
        private final long score;
        private final boolean feasible;
        private final boolean slotBlocked;
        private final boolean shiftBlocked;
        private final boolean restockBlocked;

        private Candidate(StaffState staff, List<Activity> activities, ScheduleResult schedule,
                          long score, boolean feasible, boolean slotBlocked,
                          boolean shiftBlocked, boolean restockBlocked) {
            this.staff = staff;
            this.activities = activities;
            this.schedule = schedule;
            this.score = score;
            this.feasible = feasible;
            this.slotBlocked = slotBlocked;
            this.shiftBlocked = shiftBlocked;
            this.restockBlocked = restockBlocked;
        }

        private static Candidate feasible(StaffState staff, List<Activity> activities,
                                          ScheduleResult schedule, long score) {
            return new Candidate(staff, activities, schedule, score,
                    true, false, false, false);
        }

        private static Candidate blocked(StaffState staff, boolean slotBlocked,
                                         boolean shiftBlocked, boolean restockBlocked) {
            return new Candidate(staff, List.of(), null, Long.MAX_VALUE, false,
                    slotBlocked, shiftBlocked, restockBlocked);
        }
    }

    private record MatrixView(Map<String, Integer> indexes, JsonNode durations, JsonNode distances) {
        private MatrixView(JsonNode payload) {
            this(indexPoints(payload.path("points")),
                    payload.path("durations_sec"), payload.path("distances_m"));
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

        private static Map<String, Integer> indexPoints(JsonNode points) {
            Map<String, Integer> result = new HashMap<>();
            int index = 0;
            for (JsonNode point : points) {
                result.put(point.asText(), index++);
            }
            return result;
        }
    }
}
