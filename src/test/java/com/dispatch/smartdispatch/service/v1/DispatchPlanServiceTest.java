package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.TravelMatrixSnapshotEntity;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.TravelMatrixSnapshotRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchPlanServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-26");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T08:00:00+05:00");

    @Test
    void schedulesMandatoryBreakAndRestocksAtAnOpenServiceCenter() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, true, true);
        PlanDtos.PlanResponse plan = fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        assertTrue(plan.unassigned().isEmpty());
        assertEquals(1, plan.routes().size());
        PlanDtos.RouteDto route = plan.routes().get(0);
        assertEquals("ACTIVE", route.status());

        long breaks = route.stops().stream().filter(stop -> "BREAK".equals(stop.type())).count();
        long restocks = route.stops().stream().filter(stop -> "RESTOCK".equals(stop.type())).count();
        assertEquals(1, breaks);
        assertEquals(1, restocks);

        PlanDtos.StopDto breakStop = route.stops().stream()
                .filter(stop -> "BREAK".equals(stop.type())).findFirst().orElseThrow();
        assertFalse(breakStop.start().isBefore(OffsetDateTime.parse("2026-10-26T12:00:00+05:00")));
        assertFalse(breakStop.start().isAfter(OffsetDateTime.parse("2026-10-26T14:00:00+05:00")));
        assertTrue(java.time.Duration.between(breakStop.start(), breakStop.end()).toMinutes() >= 30);
        assertEquals(0L, breakStop.travelSec());
        int breakIndex = route.stops().indexOf(breakStop);
        assertTrue(breakIndex > 0);
        assertEquals(route.stops().get(breakIndex - 1).locationId(), breakStop.locationId());
        assertEquals(route.stops().get(breakIndex - 1).end(), breakStop.arrival());

        PlanDtos.StopDto restock = route.stops().stream()
                .filter(stop -> "RESTOCK".equals(stop.type())).findFirst().orElseThrow();
        assertEquals("SC-01", restock.locationId());
        assertEquals(15L, java.time.Duration.between(restock.start(), restock.end()).toMinutes());
        assertFalse(restock.start().isBefore(OffsetDateTime.parse("2026-10-26T08:00:00+05:00")));
        assertFalse(restock.end().isAfter(OffsetDateTime.parse("2026-10-26T17:00:00+05:00")));

        PlanDtos.StopDto firstVisit = route.stops().stream()
                .filter(stop -> "ORD-20261026-0001".equals(stop.orderId())).findFirst().orElseThrow();
        PlanDtos.StopDto secondVisit = route.stops().stream()
                .filter(stop -> "ORD-20261026-0002".equals(stop.orderId())).findFirst().orElseThrow();
        assertEquals(0, firstVisit.equipmentAfter().ont());
        assertEquals(1, restock.equipmentAfter().ont());
        assertEquals(0, secondVisit.equipmentAfter().ont());

        PlanDtos.StopDto depotEnd = route.stops().get(route.stops().size() - 1);
        assertEquals("DEPOT_END", depotEnd.type());
        assertFalse(depotEnd.arrival().isAfter(OffsetDateTime.parse("2026-10-26T18:00:00+05:00")));
        verify(fixture.dayRepository).save(any(DispatchDaySnapshotEntity.class));
    }

    @Test
    void marksOrderUnassignedWhenStaffCapacityCannotCoverRequiredEquipment() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":0,"ROUTER":0,"STB":0}
                """, """
                {"ONT":0,"ROUTER":0,"STB":0}
                """, true, false);
        PlanDtos.PlanResponse plan = fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        assertEquals(1, plan.unassigned().size());
        assertEquals("NO_EQUIPMENT", plan.unassigned().get(0).reasonCode());
        assertEquals(1, plan.routes().size());
        assertTrue(plan.routes().get(0).stops().isEmpty());
        assertEquals(1, plan.summary().ordersTotal());
        assertEquals(0, plan.summary().visitsPlanned());
        assertEquals(1, plan.summary().unassigned());
    }

    @Test
    void rejectsRestockWhenEveryServiceCenterIsClosedBeforeRestockCanFinish() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, false, true);
        PlanDtos.PlanResponse plan = fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        assertEquals(1, plan.unassigned().size());
        assertEquals("NO_EQUIPMENT", plan.unassigned().get(0).reasonCode());
        assertEquals(1, plan.routes().size());
        assertTrue(plan.routes().get(0).stops().stream()
                .noneMatch(stop -> "RESTOCK".equals(stop.type())));
    }



    @Test
    void groupsThreePgpRelevantOrdersAndReportsPlanQuality() throws Exception {
        PlanDtos.PlanResponse plan = planForPgpOrders(0, 20, 40);

        assertEquals(1, plan.pgpGroups().size());
        assertEquals(3, plan.pgpGroups().get(0).orderIds().size());
        assertEquals("AN-0001", plan.pgpGroups().get(0).accessNodeId());
        assertEquals(1, plan.alerts().size());
        assertEquals("PGP_DETECTED", plan.alerts().get(0).type());
        assertTrue(plan.routes().get(0).stops().isEmpty());
        assertTrue(plan.unassigned().isEmpty());
        assertEquals(3, plan.summary().ordersTotal());
        assertEquals(0, plan.summary().visitsPlanned());
        assertEquals(0, plan.summary().unassigned());
        assertEquals(3, plan.summary().pgpOrders());
    }

    @Test
    void treatsPgpSixtyMinuteBoundaryAsInclusiveAndRejectsSixtyOneMinutes() throws Exception {
        PlanDtos.PlanResponse fiftyNineMinutes = planForPgpOrders(0, 30, 59);
        PlanDtos.PlanResponse exactlySixtyMinutes = planForPgpOrders(0, 30, 60);
        PlanDtos.PlanResponse sixtyOneMinutes = planForPgpOrders(0, 30, 61);

        assertEquals(1, fiftyNineMinutes.pgpGroups().size());
        assertEquals(1, exactlySixtyMinutes.pgpGroups().size());
        assertTrue(sixtyOneMinutes.pgpGroups().isEmpty());
        assertEquals(0, sixtyOneMinutes.summary().pgpOrders());
    }

    @Test
    void splitsTransitiveOverlappingPgpWindowsIntoDisjointGroups() throws Exception {
        PlanDtos.PlanResponse plan = planForPgpOrders(0, 20, 40, 60, 80, 100);

        assertEquals(2, plan.pgpGroups().size());
        assertEquals(3, plan.pgpGroups().get(0).orderIds().size());
        assertEquals(3, plan.pgpGroups().get(1).orderIds().size());

        Set<String> groupedIds = new HashSet<>();
        for (PlanDtos.PgpGroupDto group : plan.pgpGroups()) {
            int before = groupedIds.size();
            groupedIds.addAll(group.orderIds());
            assertEquals(before + group.orderIds().size(), groupedIds.size(),
                    "An order must not be assigned to more than one PGP group");
        }
        assertEquals(6, groupedIds.size());
        assertEquals(6, plan.summary().pgpOrders());
        assertEquals(2, plan.alerts().size());
    }

    @Test
    void preservesStartedVisitAndReplansOnlyTheUnstartedSuffix() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, true, true);

        PlanDtos.PlanResponse firstPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));
        PlanDtos.RouteDto firstRoute = firstPlan.routes().get(0);
        PlanDtos.StopDto firstVisit = firstRoute.stops().stream()
                .filter(stop -> "ORD-20261026-0001".equals(stop.orderId()))
                .findFirst().orElseThrow();
        assertEquals("VISIT", firstVisit.type());

        fixture.day.advanceModelNow(firstVisit.start().plusMinutes(5));
        PlanDtos.PlanResponse secondPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        PlanDtos.RouteDto secondRoute = secondPlan.routes().stream()
                .filter(route -> route.staffId().equals(firstRoute.staffId()))
                .findFirst().orElseThrow();
        PlanDtos.StopDto preservedVisit = secondRoute.stops().stream()
                .filter(stop -> "ORD-20261026-0001".equals(stop.orderId()))
                .findFirst().orElseThrow();

        assertEquals(firstVisit, preservedVisit,
                "H10 must preserve the started visit's staff, location and timestamps");
        assertEquals(firstPlan.planVersion() + 1, secondPlan.planVersion());
        assertTrue(secondRoute.stops().stream()
                .anyMatch(stop -> "ORD-20261026-0002".equals(stop.orderId())),
                "The not-yet-started visit should remain eligible for replanning");
        assertTrue(secondRoute.stops().stream()
                .anyMatch(stop -> "RESTOCK".equals(stop.type())),
                "The suffix must continue with the equipment inventory left by frozen work");
        assertEquals(2, secondRoute.stops().stream()
                .filter(stop -> "VISIT".equals(stop.type())).count());
    }

    @Test
    void preservesNextStopAsSoonAsItsTravelLegHasDeparted() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, true, true);

        PlanDtos.PlanResponse firstPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));
        PlanDtos.RouteDto firstRoute = firstPlan.routes().get(0);
        PlanDtos.StopDto firstVisit = firstRoute.stops().stream()
                .filter(stop -> "ORD-20261026-0001".equals(stop.orderId()))
                .findFirst().orElseThrow();

        OffsetDateTime departureTime = firstRoute.stops().get(0).end().plusSeconds(1);
        assertTrue(departureTime.isBefore(firstVisit.arrival()),
                "The scenario must advance model time while the first visit is still en route");

        fixture.day.advanceModelNow(departureTime);
        PlanDtos.PlanResponse secondPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        PlanDtos.RouteDto secondRoute = secondPlan.routes().stream()
                .filter(route -> route.staffId().equals(firstRoute.staffId()))
                .findFirst().orElseThrow();
        PlanDtos.StopDto preservedVisit = secondRoute.stops().stream()
                .filter(stop -> "ORD-20261026-0001".equals(stop.orderId()))
                .findFirst().orElseThrow();

        assertEquals(firstVisit, preservedVisit,
                "H10 must freeze the destination and planned schedule after departure");
        assertEquals(firstPlan.planVersion() + 1, secondPlan.planVersion());
    }

    @Test
    void doesNotInsertASecondBreakWhileTheOriginalBreakIsInProgress() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, true, true);

        PlanDtos.PlanResponse firstPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));
        PlanDtos.RouteDto firstRoute = firstPlan.routes().get(0);
        PlanDtos.StopDto originalBreak = firstRoute.stops().stream()
                .filter(stop -> "BREAK".equals(stop.type()))
                .findFirst().orElseThrow();

        fixture.day.advanceModelNow(originalBreak.start().plusSeconds(1));
        PlanDtos.PlanResponse secondPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        PlanDtos.RouteDto secondRoute = secondPlan.routes().get(0);
        assertEquals(1, secondRoute.stops().stream()
                .filter(stop -> "BREAK".equals(stop.type())).count());
        assertEquals(originalBreak, secondRoute.stops().stream()
                .filter(stop -> "BREAK".equals(stop.type()))
                .findFirst().orElseThrow());
    }

    @Test
    void preservesEntireRouteOnceReturnLegHasStarted() throws Exception {
        Fixture fixture = fixture("""
                {"ONT":1,"ROUTER":0,"STB":0}
                """, """
                {"ONT":1,"ROUTER":0,"STB":0}
                """, true, true);

        PlanDtos.PlanResponse firstPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));
        PlanDtos.RouteDto firstRoute = firstPlan.routes().get(0);
        PlanDtos.StopDto depotEnd = firstRoute.stops().get(firstRoute.stops().size() - 1);

        fixture.day.advanceModelNow(depotEnd.end().plusSeconds(1));
        PlanDtos.PlanResponse secondPlan =
                fixture.service.generate(DATE, new PlanDtos.PlanRequest(60));

        PlanDtos.RouteDto secondRoute = secondPlan.routes().stream()
                .filter(route -> route.staffId().equals(firstRoute.staffId()))
                .findFirst().orElseThrow();

        assertEquals(firstRoute.stops(), secondRoute.stops(),
                "No work may be appended after the previous plan's return leg has started");
        assertEquals(firstRoute.totals(), secondRoute.totals());
        assertEquals(firstPlan.planVersion() + 1, secondPlan.planVersion());
    }

    private PlanDtos.PlanResponse planForPgpOrders(int... minuteOffsets) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        String referenceJson = """
                {
                  "service_centers": [
                    {"service_center_id":"SC-01","opening_hours":{"open":"08:00","close":"18:00"},"restock_duration_min":15}
                  ],
                  "staff": [
                    {
                      "staff_id":"ST-001","service_center_id":"SC-01",
                      "profiles":["INSTALL"],"technologies":["PON"],"height_permit":false,
                      "shift":{"start":"08:00","end":"18:00","break_duration_min":30,
                               "break_window":{"from":"12:00","to":"14:00"}},
                      "equipment_start":{"ONT":10,"ROUTER":10,"STB":10},
                      "equipment_capacity":{"ONT":10,"ROUTER":10,"STB":10}
                    }
                  ],
                  "work_types":[
                    {"code":"REPAIR","profiles":["INSTALL"],"duration_min":30,"pgp_relevant":true}
                  ]
                }
                """;

        OffsetDateTime registrationStart = OffsetDateTime.parse("2026-10-26T08:00:00+05:00");
        String orderTemplate = """
                {
                  "order_id":"ORD-20261026-%04d","work_type_code":"REPAIR","technology":"PON",
                  "building_id":"B-%05d","requires_height":false,"access_node_id":"AN-0001",
                  "registered_at":"%s",
                  "slot":{"start":"2026-10-26T10:00:00+05:00","end":"2026-10-26T12:00:00+05:00"},
                  "latest_start":"2026-10-26T10:30:00+05:00","deadline":"2026-10-26T11:00:00+05:00",
                  "required_equipment":{"ONT":0,"ROUTER":0,"STB":0}
                }
                """;
        List<String> orders = new ArrayList<>();
        List<String> pointIds = new ArrayList<>();
        pointIds.add("SC-01");
        for (int i = 0; i < minuteOffsets.length; i++) {
            String registeredAt = registrationStart.plusMinutes(minuteOffsets[i]).toString();
            orders.add(orderTemplate.formatted(i + 1, i + 1, registeredAt));
            pointIds.add("B-%05d".formatted(i + 1));
        }
        String dayJson = """
                {"absent_staff_ids":[],"orders":[%s]}
                """.formatted(String.join(",", orders));
        String pointsJson = pointIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(java.util.stream.Collectors.joining(","));
        String matrix = matrixJson(pointIds.size());
        String matrixJson = """
                {"points":[%s],"durations_sec":%s,"distances_m":%s}
                """.formatted(pointsJson, matrix, matrix);

        DispatchDaySnapshotRepository dayRepository = mock(DispatchDaySnapshotRepository.class);
        ReferenceSnapshotRepository referenceRepository = mock(ReferenceSnapshotRepository.class);
        TravelMatrixSnapshotRepository matrixRepository = mock(TravelMatrixSnapshotRepository.class);
        DispatchDaySnapshotEntity day = new DispatchDaySnapshotEntity(
                DATE, NOW, mapper.readTree(dayJson), minuteOffsets.length);
        ReferenceSnapshotEntity reference = new ReferenceSnapshotEntity(
                mapper.readTree(referenceJson), 1, 1, 1, minuteOffsets.length, 1);
        TravelMatrixSnapshotEntity travelMatrix = new TravelMatrixSnapshotEntity(
                "PGP-MATRIX", mapper.readTree(matrixJson), pointIds.size());

        when(dayRepository.findByDateForUpdate(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1)).thenReturn(Optional.of(reference));
        when(matrixRepository.findById((short) 1)).thenReturn(Optional.of(travelMatrix));

        DispatchPlanService service = new DispatchPlanService(
                dayRepository, referenceRepository, matrixRepository, mapper);
        return service.generate(DATE, new PlanDtos.PlanRequest(60));
    }

    private String matrixJson(int size) {
        StringBuilder matrix = new StringBuilder("[");
        for (int row = 0; row < size; row++) {
            if (row > 0) matrix.append(",");
            matrix.append("[");
            for (int column = 0; column < size; column++) {
                if (column > 0) matrix.append(",");
                matrix.append(row == column ? 0 : 300);
            }
            matrix.append("]");
        }
        return matrix.append("]").toString();
    }

    private Fixture fixture(String startingEquipment, String capacity, boolean homeCenterOpen,
                            boolean includeSecondOrder) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);

        String homeClose = homeCenterOpen ? "17:00" : "09:45";
        String referenceJson = """
                {
                  "service_centers": [
                    {"service_center_id":"SC-01","opening_hours":{"open":"08:00","close":"%s"},"restock_duration_min":15},
                    {"service_center_id":"SC-02","opening_hours":{"open":"08:00","close":"09:30"},"restock_duration_min":15}
                  ],
                  "staff": [
                    {
                      "staff_id":"ST-001","service_center_id":"SC-01",
                      "profiles":["INSTALL"],"technologies":["PON"],"height_permit":false,
                      "shift":{"start":"08:00","end":"18:00","break_duration_min":30,
                               "break_window":{"from":"12:00","to":"14:00"}},
                      "equipment_start":%s,"equipment_capacity":%s
                    }
                  ],
                  "work_types":[
                    {"code":"REPAIR","profiles":["INSTALL"],"duration_min":30}
                  ]
                }
                """.formatted(homeClose, startingEquipment, capacity);

        String firstOrder = order("0001", "B-00001",
                "2026-10-26T09:00:00+05:00", "2026-10-26T09:30:00+05:00",
                "2026-10-26T10:00:00+05:00");
        String secondOrder = order("0002", "B-00002",
                "2026-10-26T13:00:00+05:00", "2026-10-26T13:10:00+05:00",
                "2026-10-26T13:40:00+05:00");
        String dayJson = """
                {"absent_staff_ids":[],"orders":[%s%s]}
                """.formatted(firstOrder, includeSecondOrder ? "," + secondOrder : "");

        String matrixJson = """
                {
                  "points":["SC-01","SC-02","B-00001","B-00002"],
                  "durations_sec":[
                    [0,300,600,900],
                    [300,0,300,600],
                    [600,300,0,300],
                    [900,600,300,0]
                  ],
                  "distances_m":[
                    [0,3000,6000,9000],
                    [3000,0,3000,6000],
                    [6000,3000,0,3000],
                    [9000,6000,3000,0]
                  ]
                }
                """;

        DispatchDaySnapshotRepository dayRepository = mock(DispatchDaySnapshotRepository.class);
        ReferenceSnapshotRepository referenceRepository = mock(ReferenceSnapshotRepository.class);
        TravelMatrixSnapshotRepository matrixRepository = mock(TravelMatrixSnapshotRepository.class);

        DispatchDaySnapshotEntity day = new DispatchDaySnapshotEntity(
                DATE, NOW, mapper.readTree(dayJson), includeSecondOrder ? 2 : 1);
        ReferenceSnapshotEntity reference = new ReferenceSnapshotEntity(
                mapper.readTree(referenceJson), 2, 1, 0, 0, 1);
        TravelMatrixSnapshotEntity matrix = new TravelMatrixSnapshotEntity(
                "TEST-MATRIX", mapper.readTree(matrixJson), 4);

        when(dayRepository.findByDateForUpdate(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1)).thenReturn(Optional.of(reference));
        when(matrixRepository.findById((short) 1)).thenReturn(Optional.of(matrix));

        DispatchPlanService service = new DispatchPlanService(
                dayRepository, referenceRepository, matrixRepository, mapper);
        return new Fixture(service, dayRepository, day);
    }

    private String order(String suffix, String buildingId, String slotStart,
                         String latestStart, String deadline) {
        return """
                {
                  "order_id":"ORD-20261026-%s","work_type_code":"REPAIR","technology":"PON",
                  "building_id":"%s","requires_height":false,
                  "slot":{"start":"%s","end":"%s"},
                  "latest_start":"%s","deadline":"%s",
                  "required_equipment":{"ONT":1,"ROUTER":0,"STB":0}
                }
                """.formatted(suffix, buildingId, slotStart,
                slotStart.startsWith("2026-10-26T09") ? "2026-10-26T11:00:00+05:00"
                        : "2026-10-26T15:00:00+05:00",
                latestStart, deadline);
    }

    private record Fixture(DispatchPlanService service, DispatchDaySnapshotRepository dayRepository,
                           DispatchDaySnapshotEntity day) { }
}
