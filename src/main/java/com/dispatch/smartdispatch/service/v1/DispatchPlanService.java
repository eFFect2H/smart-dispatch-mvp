package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.*;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.EquipmentDto;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.TimeWindowDto;
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
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

@Service
public class DispatchPlanService {
    private static final String VISIT = "VISIT";
    private static final String RESTOCK = "RESTOCK";
    private static final String BREAK = "BREAK";
    private static final String DEPOT_END = "DEPOT_END";
    private static final Duration PGP_WINDOW = Duration.ofMinutes(60);

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

        PgpDetection pgp = detectPgpGroups(date, orders, workTypes);
        Set<String> pgpOrderIds = pgp.orderIds;
        List<UnassignedOrderDto> unassigned = new ArrayList<>();
        List<AlertDto> alerts = new ArrayList<>(pgp.alerts);

        for (JsonNode order : orders) {
            String orderId = order.path("order_id").asText();
            if (pgpOrderIds.contains(orderId)) {
                continue;
            }

            JsonNode workType = workTypes.get(order.path("work_type_code").asText());
            if (workType == null) {
                addUnassigned(unassigned, alerts, orderId, "OTHER",
                        "No work type definition is available for this order", "ESCALATE", day.getModelNow());
                continue;
            }

            List<StaffState> qualified = staff.stream()
                    .filter(candidate -> isQualified(candidate, order, workType))
                    .toList();
            if (qualified.isEmpty()) {
                addUnassigned(unassigned, alerts, orderId, "NO_QUALIFIED_STAFF",
                        "No staff member satisfies the order profile, technology and height requirements",
                        "RESCHEDULE", day.getModelNow());
                continue;
            }

            List<StaffState> available = qualified.stream()
                    .filter(candidate -> !candidate.unavailable)
                    .toList();
            if (available.isEmpty()) {
                addUnassigned(unassigned, alerts, orderId, "STAFF_UNAVAILABLE",
                        "All qualified staff members are marked absent for this day",
                        "REASSIGN", day.getModelNow());
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
                    addUnassigned(unassigned, alerts, orderId, "NO_EQUIPMENT",
                            "Required equipment is unavailable and no valid restock stop can be scheduled",
                            "RESCHEDULE", day.getModelNow());
                } else if (slotBlocked) {
                    addUnassigned(unassigned, alerts, orderId, "SLOT_UNREACHABLE",
                            "No qualified available staff member can reach the order within its allowed start window",
                            "RESCHEDULE", day.getModelNow());
                } else if (shiftBlocked) {
                    addUnassigned(unassigned, alerts, orderId, "NO_SHIFT_TIME",
                            "The visit, mandatory break and return to the service center do not fit inside a qualified staff shift",
                            "RESCHEDULE", day.getModelNow());
                } else {
                    addUnassigned(unassigned, alerts, orderId, "OTHER",
                            "No feasible route insertion was found", "ESCALATE", day.getModelNow());
                }
                continue;
            }

            best.staff.activities.clear();
            best.staff.activities.addAll(best.activities);
            best.staff.equipmentRemaining = best.schedule.finalEquipment;
        }

        List<RouteDto> routes = buildRoutes(staff, centers, matrix);
        PlanQuality quality = calculateQuality(orders.size(), routes, unassigned.size(),
                pgp.groups, 0);
        long computationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        PlanResponse response = new PlanResponse(
                date,
                day.getPlanVersion() + 1,
                day.getModelNow(),
                routes,
                unassigned,
                pgp.groups,
                List.of(),
                alerts,
                computationMs,
                quality.summary
        );

        validatePlan(response, orders, workTypes, reference, matrix, centers,
                day.getPayload(), date, day.getModelNow());

        try {
            day.updatePlan(response.planVersion(), objectMapper.valueToTree(response), day.getModelNow());
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
                routes.add(new RouteDto(state.staffId, state.serviceCenterId, "UNAVAILABLE",
                        List.of(), emptyTotals()));
                continue;
            }
            if (state.activities.isEmpty()) {
                routes.add(new RouteDto(state.staffId, state.serviceCenterId, "ACTIVE",
                        List.of(), emptyTotals()));
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
            routes.add(new RouteDto(state.staffId, state.serviceCenterId, "ACTIVE",
                    stops, routeTotals(state, schedule)));
        }
        return routes;
    }

    private PgpDetection detectPgpGroups(LocalDate date, List<JsonNode> orders,
                                         Map<String, JsonNode> workTypes) {
        Map<String, List<JsonNode>> byAccessNode = new TreeMap<>();
        for (JsonNode order : orders) {
            JsonNode workType = workTypes.get(order.path("work_type_code").asText());
            if (workType == null || !workType.path("pgp_relevant").asBoolean(false)) {
                continue;
            }
            byAccessNode.computeIfAbsent(order.path("access_node_id").asText(), ignored -> new ArrayList<>())
                    .add(order);
        }

        List<PgpGroupDto> groups = new ArrayList<>();
        List<AlertDto> alerts = new ArrayList<>();
        Set<String> groupedOrderIds = new HashSet<>();
        int groupSequence = 1;
        int alertSequence = 1;

        for (Map.Entry<String, List<JsonNode>> entry : byAccessNode.entrySet()) {
            List<JsonNode> candidates = entry.getValue();
            candidates.sort(Comparator.comparing(order -> parseTime(order.path("registered_at"))));
            List<GroupWindow> windows = new ArrayList<>();

            for (int start = 0; start < candidates.size(); start++) {
                int end = start;
                OffsetDateTime startTime = parseTime(candidates.get(start).path("registered_at"));
                while (end < candidates.size()
                        && Duration.between(startTime,
                        parseTime(candidates.get(end).path("registered_at"))).compareTo(PGP_WINDOW) <= 0) {
                    end++;
                }
                if (end - start < 3) {
                    continue;
                }

                if (!windows.isEmpty() && start <= windows.get(windows.size() - 1).endIndex) {
                    GroupWindow previous = windows.get(windows.size() - 1);
                    previous.endIndex = Math.max(previous.endIndex, end - 1);
                } else {
                    OffsetDateTime detectedAt = parseTime(candidates.get(start + 2).path("registered_at"));
                    windows.add(new GroupWindow(start, end - 1, detectedAt));
                }
            }

            for (GroupWindow window : windows) {
                List<String> ids = new ArrayList<>();
                for (int i = window.startIndex; i <= window.endIndex; i++) {
                    String id = candidates.get(i).path("order_id").asText();
                    if (groupedOrderIds.add(id)) {
                        ids.add(id);
                    }
                }
                if (ids.size() < 3) {
                    continue;
                }

                String groupId = "PGP-" + date.toString().replace("-", "") + "-"
                        + String.format(Locale.ROOT, "%02d", groupSequence++);
                String comment = ids.size() + " обращений с узла доступа за 60 минут";
                groups.add(new PgpGroupDto(groupId, entry.getKey(), ids,
                        window.detectedAt, "TRANSFER_TO_STATION", comment));
                alerts.add(new AlertDto(
                        "ALR-" + date.toString().replace("-", "") + "-"
                                + String.format(Locale.ROOT, "%04d", alertSequence++),
                        window.detectedAt,
                        "PGP_DETECTED",
                        "ACTIVE",
                        "TRANSFER_TO_STATION",
                        "ПГП на " + entry.getKey() + ": " + ids.size()
                                + " наряда переданы в станционное подразделение",
                        null, null, groupId, null, null, null, null, null, true
                ));
            }
        }
        return new PgpDetection(groups, groupedOrderIds, alerts);
    }

    private void addUnassigned(List<UnassignedOrderDto> unassigned, List<AlertDto> alerts,
                               String orderId, String reasonCode, String reasonText,
                               String action, OffsetDateTime asOf) {
        unassigned.add(new UnassignedOrderDto(orderId, reasonCode, reasonText, action, null, asOf));
        alerts.add(new AlertDto(
                "ALR-" + asOf.toLocalDate().toString().replace("-", "") + "-"
                        + String.format(Locale.ROOT, "%04d", alerts.size() + 1),
                asOf, "UNASSIGNED", "ACTIVE", action, reasonText,
                null, orderId, null, null, null, null, null, null, false
        ));
    }

    private PlanQuality calculateQuality(int ordersTotal, List<RouteDto> routes,
                                         int unassignedCount, List<PgpGroupDto> pgpGroups,
                                         int cancelledCount) {
        int visits = 0;
        long travelSecTotal = 0L;
        int pgpOrders = 0;
        List<Double> utilization = new ArrayList<>();

        for (RouteDto route : routes) {
            if (route.totals() == null) {
                continue;
            }
            visits += route.totals().visits();
            travelSecTotal += route.totals().travelSec();
            if ("ACTIVE".equals(route.status())) {
                utilization.add(route.totals().utilization());
            }
        }
        for (PgpGroupDto group : pgpGroups) {
            pgpOrders += group.orderIds().size();
        }

        return new PlanQuality(new PlanSummary(
                ordersTotal, visits, unassignedCount, pgpOrders, cancelledCount,
                travelSecTotal, coefficientOfVariation(utilization)
        ));
    }

    private double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        if (mean <= 0.0) {
            return 0.0;
        }
        double variance = values.stream()
                .mapToDouble(value -> (value - mean) * (value - mean))
                .average().orElse(0.0);
        return Math.sqrt(variance) / mean;
    }

    private RouteTotals routeTotals(StaffState staff, ScheduleResult schedule) {
        int workMin = 0;
        int visits = 0;
        for (ScheduledActivity activity : schedule.activities) {
            if (VISIT.equals(activity.type)) {
                visits++;
                workMin += Math.toIntExact(Duration.between(activity.start, activity.end).toMinutes());
            }
        }
        long shiftMinutes = Duration.between(staff.shiftStart, staff.shiftEnd).toMinutes();
        long availableMinutes = Math.max(0L, shiftMinutes - staff.breakDurationMin);
        double utilization = availableMinutes == 0L ? 0.0
                : (workMin + schedule.totalTravelSec / 60.0) / availableMinutes;
        return new RouteTotals(visits, schedule.totalTravelSec,
                schedule.totalDistanceM, workMin, utilization);
    }

    private RouteTotals emptyTotals() {
        return new RouteTotals(0, 0L, 0L, 0, 0.0);
    }

    private void validatePlan(PlanResponse plan, List<JsonNode> inputOrders,
                              Map<String, JsonNode> workTypes, JsonNode reference,
                              MatrixView matrix, Map<String, ServiceCenter> centers,
                              JsonNode dayPayload, LocalDate date, OffsetDateTime modelNow) {
        Map<String, JsonNode> ordersById = new HashMap<>();
        inputOrders.forEach(order -> ordersById.put(order.path("order_id").asText(), order));
        Map<String, JsonNode> staffById = indexBy(reference.path("staff"), "staff_id");
        Set<String> absent = values(dayPayload.path("absent_staff_ids"));
        Map<String, Integer> placements = new HashMap<>();
        Set<String> routeStaffIds = new HashSet<>();

        if (plan.routes().size() != staffById.size()) {
            throw new IllegalStateException("H9/route coverage: expected one route per staff member");
        }

        for (RouteDto route : plan.routes()) {
            JsonNode staffNode = staffById.get(route.staffId());
            if (staffNode == null || !routeStaffIds.add(route.staffId())) {
                throw new IllegalStateException("Unknown or duplicate staff route: " + route.staffId());
            }
            boolean unavailable = absent.contains(route.staffId());
            if (unavailable) {
                if (!"UNAVAILABLE".equals(route.status()) || !route.stops().isEmpty()) {
                    throw new IllegalStateException("Unavailable staff must have an empty UNAVAILABLE route");
                }
                continue;
            }
            if (!"ACTIVE".equals(route.status())) {
                throw new IllegalStateException("Available staff route must have ACTIVE status");
            }
            if (route.stops().isEmpty()) {
                continue;
            }

            JsonNode shift = staffNode.path("shift");
            OffsetDateTime shiftStart = at(date, LocalTime.parse(shift.path("start").asText()), modelNow);
            OffsetDateTime shiftEnd = at(date, LocalTime.parse(shift.path("end").asText()), modelNow);
            OffsetDateTime breakFrom = at(date,
                    LocalTime.parse(shift.path("break_window").path("from").asText()), modelNow);
            OffsetDateTime breakTo = at(date,
                    LocalTime.parse(shift.path("break_window").path("to").asText()), modelNow);
            int breakDurationMin = shift.path("break_duration_min").asInt();
            String homeCenterId = staffNode.path("service_center_id").asText();
            List<StopDto> stops = route.stops();

            if (!"DEPOT_START".equals(stops.get(0).type())
                    || !"DEPOT_END".equals(stops.get(stops.size() - 1).type())) {
                throw new IllegalStateException("H8: route must start and end at a depot stop");
            }
            StopDto depotStart = stops.get(0);
            if (!homeCenterId.equals(depotStart.locationId())
                    || !depotStart.arrival().equals(depotStart.start())
                    || !depotStart.start().equals(depotStart.end())
                    || depotStart.start().isBefore(shiftStart)) {
                throw new IllegalStateException("H2/H8: invalid DEPOT_START");
            }

            int breakCount = 0;
            Equipment inventory = equipment(staffNode.path("equipment_start"));
            Equipment capacity = equipment(staffNode.path("equipment_capacity"));
            assertEquipment(depotStart.equipmentAfter(), inventory, "DEPOT_START");

            StopDto previous = depotStart;
            for (int index = 1; index < stops.size(); index++) {
                StopDto stop = stops.get(index);
                if (stop.seq() != index + 1) {
                    throw new IllegalStateException("Stop seq must be contiguous for " + route.staffId());
                }
                if (stop.arrival().isBefore(shiftStart)
                        || stop.start().isBefore(shiftStart)
                        || stop.end().isAfter(shiftEnd)
                        || stop.start().isBefore(stop.arrival())
                        || stop.end().isBefore(stop.start())) {
                    throw new IllegalStateException("H2/H6: stop time is outside shift or has negative duration");
                }

                if (BREAK.equals(stop.type())) {
                    breakCount++;
                    if (breakCount > 1
                            || !stop.locationId().equals(previous.locationId())
                            || stop.travelSec() == null || stop.travelSec() != 0L
                            || !stop.arrival().equals(previous.end())
                            || stop.start().isBefore(breakFrom) || stop.start().isAfter(breakTo)
                            || Duration.between(stop.start(), stop.end()).toMinutes() < breakDurationMin) {
                        throw new IllegalStateException("H3: invalid mandatory BREAK stop");
                    }
                } else {
                    long expectedTravel = matrix.duration(previous.locationId(), stop.locationId());
                    OffsetDateTime earliestArrival = previous.end().plusSeconds(expectedTravel);
                    if (stop.arrival().isBefore(earliestArrival)) {
                        throw new IllegalStateException("H6: arrival precedes matrix travel time");
                    }
                    if (stop.travelSec() == null || stop.travelSec() < expectedTravel) {
                        throw new IllegalStateException("H6: travel_sec is smaller than the matrix value");
                    }
                }

                switch (stop.type()) {
                    case VISIT -> {
                        JsonNode order = ordersById.get(stop.orderId());
                        if (order == null || !stop.locationId().equals(order.path("building_id").asText())) {
                            throw new IllegalStateException("H9/H6: VISIT references unknown order or building");
                        }
                        placements.merge(stop.orderId(), 1, Integer::sum);
                        JsonNode workType = workTypes.get(order.path("work_type_code").asText());
                        if (workType == null || !isQualified(
                                staffNode, order, workType)) {
                            throw new IllegalStateException("H1: VISIT staff does not meet qualification requirements");
                        }
                        if (stop.start().isBefore(parseTime(order.path("slot").path("start")))
                                || stop.start().isAfter(parseTime(order.path("latest_start")))) {
                            throw new IllegalStateException("H4: VISIT starts outside allowed slot");
                        }
                        long duration = Duration.between(stop.start(), stop.end()).toMinutes();
                        if (duration != workType.path("duration_min").asLong()
                                || stop.end().isAfter(parseTime(order.path("deadline")))) {
                            throw new IllegalStateException("H5: VISIT duration or deadline is invalid");
                        }
                        Equipment required = equipment(order.path("required_equipment"));
                        if (!inventory.canCover(required)) {
                            throw new IllegalStateException("H7: equipment inventory became negative");
                        }
                        inventory = inventory.subtract(required);
                    }
                    case RESTOCK -> {
                        ServiceCenter center = centers.get(stop.locationId());
                        if (center == null) {
                            throw new IllegalStateException("H8: RESTOCK references unknown service center");
                        }
                        OffsetDateTime open = at(date, center.opening, modelNow);
                        OffsetDateTime close = at(date, center.closing, modelNow);
                        if (stop.start().isBefore(open) || stop.end().isAfter(close)
                                || Duration.between(stop.start(), stop.end()).toMinutes()
                                < center.restockDurationMin) {
                            throw new IllegalStateException("H8: RESTOCK is outside service center opening hours");
                        }
                        inventory = capacity;
                    }
                    case BREAK -> { /* Inventory does not change during a break. */ }
                    case DEPOT_END -> {
                        if (!homeCenterId.equals(stop.locationId())
                                || !stop.arrival().equals(stop.start())
                                || !stop.start().equals(stop.end())) {
                            throw new IllegalStateException("H8: invalid DEPOT_END");
                        }
                    }
                    default -> throw new IllegalStateException("Unknown stop type: " + stop.type());
                }
                assertEquipment(stop.equipmentAfter(), inventory, stop.type());
                previous = stop;
            }

            boolean hasVisits = stops.stream().anyMatch(stop -> VISIT.equals(stop.type()));
            if ((hasVisits && breakCount != 1) || (!hasVisits && breakCount != 0)) {
                throw new IllegalStateException("H3: route must contain exactly one BREAK when it has visits");
            }
            StopDto depotEnd = stops.get(stops.size() - 1);
            if (depotEnd.arrival().isAfter(shiftEnd)) {
                throw new IllegalStateException("H2: DEPOT_END is after shift end");
            }
        }

        for (UnassignedOrderDto order : plan.unassigned()) {
            placements.merge(order.orderId(), 1, Integer::sum);
        }
        for (PgpGroupDto group : plan.pgpGroups()) {
            if (new HashSet<>(group.orderIds()).size() != group.orderIds().size()
                    || group.orderIds().size() < 3) {
                throw new IllegalStateException("PGP group must contain at least three unique orders");
            }
            for (String orderId : group.orderIds()) {
                JsonNode order = ordersById.get(orderId);
                if (order == null) {
                    throw new IllegalStateException("PGP group references unknown order " + orderId);
                }
                JsonNode workType = workTypes.get(order.path("work_type_code").asText());
                if (workType == null || !workType.path("pgp_relevant").asBoolean(false)
                        || !group.accessNodeId().equals(order.path("access_node_id").asText())) {
                    throw new IllegalStateException("PGP group contains an ineligible order");
                }
                placements.merge(orderId, 1, Integer::sum);
            }
        }
        for (CancelledOrderDto cancelled : plan.cancelled()) {
            placements.merge(cancelled.orderId(), 1, Integer::sum);
        }

        if (!placements.keySet().equals(ordersById.keySet())
                || placements.values().stream().anyMatch(count -> count != 1)) {
            throw new IllegalStateException("H9: each input order must appear exactly once in the plan");
        }
    }

    private boolean isQualified(JsonNode staff, JsonNode order, JsonNode workType) {
        Set<String> staffProfiles = values(staff.path("profiles"));
        Set<String> requiredProfiles = values(workType.path("profiles"));
        boolean profileMatch = staffProfiles.stream().anyMatch(requiredProfiles::contains);
        boolean technologyMatch = values(staff.path("technologies"))
                .contains(order.path("technology").asText());
        boolean heightMatch = !order.path("requires_height").asBoolean()
                || staff.path("height_permit").asBoolean();
        return profileMatch && technologyMatch && heightMatch;
    }

    private void assertEquipment(EquipmentDto actual, Equipment expected, String stopType) {
        if (actual == null || actual.ont() != expected.ont
                || actual.router() != expected.router || actual.stb() != expected.stb) {
            throw new IllegalStateException("H7: equipment_after is inconsistent at " + stopType);
        }
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

    private record PgpDetection(List<PgpGroupDto> groups, Set<String> orderIds,
                                List<AlertDto> alerts) { }

    private static final class GroupWindow {
        private final int startIndex;
        private int endIndex;
        private final OffsetDateTime detectedAt;

        private GroupWindow(int startIndex, int endIndex, OffsetDateTime detectedAt) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.detectedAt = detectedAt;
        }
    }

    private record PlanQuality(PlanSummary summary) { }

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
