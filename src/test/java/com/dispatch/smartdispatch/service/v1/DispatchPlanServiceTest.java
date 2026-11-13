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
import java.util.Optional;

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
        assertTrue(plan.routes().isEmpty());
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

        when(dayRepository.findById(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1)).thenReturn(Optional.of(reference));
        when(matrixRepository.findById((short) 1)).thenReturn(Optional.of(matrix));

        DispatchPlanService service = new DispatchPlanService(
                dayRepository, referenceRepository, matrixRepository, mapper);
        return new Fixture(service, dayRepository);
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

    private record Fixture(DispatchPlanService service, DispatchDaySnapshotRepository dayRepository) { }
}
