package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.RouteGeometryDtos.RouteGeometryResponse;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.routing.Coordinate;
import com.dispatch.smartdispatch.routing.OsrmMatrixClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class RouteGeometryServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-11-14");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-14T08:45:00+05:00");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestsRoadGeometryUsingUniqueConsecutiveWaypointsInStopOrder() throws Exception {
        DispatchDaySnapshotRepository dayRepository = mock(DispatchDaySnapshotRepository.class);
        ReferenceSnapshotRepository referenceRepository = mock(ReferenceSnapshotRepository.class);
        OsrmMatrixClient osrm = mock(OsrmMatrixClient.class);

        JsonNode plan = mapper.readTree("""
                {
                  "routes": [{
                    "staff_id": "ST-001",
                    "stops": [
                      {"seq":1,"type":"DEPOT_START","location_id":"SC-01"},
                      {"seq":2,"type":"VISIT","location_id":"B-00001"},
                      {"seq":3,"type":"BREAK","location_id":"B-00001"},
                      {"seq":4,"type":"RESTOCK","location_id":"SC-02"},
                      {"seq":5,"type":"VISIT","location_id":"B-00002"},
                      {"seq":6,"type":"DEPOT_END","location_id":"SC-01"}
                    ]
                  }]
                }
                """);
        JsonNode referenceJson = mapper.readTree("""
                {
                  "service_centers": [
                    {"service_center_id":"SC-01","lat":56.8382,"lon":60.6008},
                    {"service_center_id":"SC-02","lat":56.8150,"lon":60.5700}
                  ],
                  "access_nodes": [
                    {"buildings":[{"building_id":"B-00001","lat":56.8400,"lon":60.6110}]},
                    {"buildings":[{"building_id":"B-00002","lat":56.8415,"lon":60.6190}]}
                  ]
                }
                """);

        DispatchDaySnapshotEntity day = new DispatchDaySnapshotEntity(DATE, NOW, mapper.createObjectNode(), 2);
        day.updatePlan(4, plan, NOW);
        ReferenceSnapshotEntity reference = new ReferenceSnapshotEntity(referenceJson, 2, 1, 2, 2, 1);
        when(dayRepository.findById(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1)).thenReturn(Optional.of(reference));

        List<Coordinate> road = List.of(
                new Coordinate(60.6008, 56.8382),
                new Coordinate(60.6040, 56.8390),
                new Coordinate(60.6110, 56.8400),
                new Coordinate(60.6160, 56.8408),
                new Coordinate(60.6190, 56.8415),
                new Coordinate(60.6008, 56.8382)
        );
        when(osrm.getRouteGeometry(anyList())).thenReturn(road);

        RouteGeometryService service = new RouteGeometryService(dayRepository, referenceRepository, osrm);
        RouteGeometryResponse response = service.getRouteGeometries(DATE);

        assertEquals(DATE, response.date());
        assertEquals(4, response.planVersion());
        assertEquals(1, response.routes().size());
        assertEquals("ST-001", response.routes().get(0).staffId());
        assertEquals(road.size(), response.routes().get(0).points().size());
        assertEquals(56.8382, response.routes().get(0).points().get(0).lat(), 0.000001);
        assertEquals(60.6008, response.routes().get(0).points().get(0).lon(), 0.000001);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Coordinate>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(osrm).getRouteGeometry(captor.capture());
        assertEquals(List.of(
                new Coordinate(60.6008, 56.8382),
                new Coordinate(60.6110, 56.8400),
                new Coordinate(60.5700, 56.8150),
                new Coordinate(60.6190, 56.8415),
                new Coordinate(60.6008, 56.8382)
        ), captor.getValue());
    }

    @Test
    void keepsRouteWithoutRoadLineWhenThereAreNotTwoDistinctStops() throws Exception {
        DispatchDaySnapshotRepository dayRepository = mock(DispatchDaySnapshotRepository.class);
        ReferenceSnapshotRepository referenceRepository = mock(ReferenceSnapshotRepository.class);
        OsrmMatrixClient osrm = mock(OsrmMatrixClient.class);

        JsonNode plan = mapper.readTree("""
                {
                  "routes": [{
                    "staff_id": "ST-001",
                    "stops": [
                      {"seq":1,"type":"DEPOT_START","location_id":"SC-01"},
                      {"seq":2,"type":"DEPOT_END","location_id":"SC-01"}
                    ]
                  }]
                }
                """);
        JsonNode referenceJson = mapper.readTree("""
                {"service_centers":[{"service_center_id":"SC-01","lat":56.8382,"lon":60.6008}]}
                """);
        DispatchDaySnapshotEntity day = new DispatchDaySnapshotEntity(DATE, NOW, mapper.createObjectNode(), 0);
        day.updatePlan(1, plan, NOW);
        when(dayRepository.findById(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1))
                .thenReturn(Optional.of(new ReferenceSnapshotEntity(referenceJson, 1, 1, 0, 0, 0)));

        RouteGeometryService service = new RouteGeometryService(dayRepository, referenceRepository, osrm);
        RouteGeometryResponse response = service.getRouteGeometries(DATE);

        assertEquals(1, response.routes().size());
        assertTrue(response.routes().get(0).points().isEmpty());
        verifyNoInteractions(osrm);
    }

    @Test
    void leavesGeometryEmptyWhenOsrmCannotFindRoadRoute() throws Exception {
        DispatchDaySnapshotRepository dayRepository = mock(DispatchDaySnapshotRepository.class);
        ReferenceSnapshotRepository referenceRepository = mock(ReferenceSnapshotRepository.class);
        OsrmMatrixClient osrm = mock(OsrmMatrixClient.class);

        JsonNode plan = mapper.readTree("""
                {"routes":[{"staff_id":"ST-001","stops":[
                  {"seq":1,"location_id":"SC-01"},{"seq":2,"location_id":"B-00001"}
                ]}]}
                """);
        JsonNode referenceJson = mapper.readTree("""
                {
                  "service_centers":[{"service_center_id":"SC-01","lat":56.8382,"lon":60.6008}],
                  "access_nodes":[{"buildings":[{"building_id":"B-00001","lat":56.8400,"lon":60.6110}]}]
                }
                """);
        DispatchDaySnapshotEntity day = new DispatchDaySnapshotEntity(DATE, NOW, mapper.createObjectNode(), 1);
        day.updatePlan(2, plan, NOW);
        when(dayRepository.findById(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1))
                .thenReturn(Optional.of(new ReferenceSnapshotEntity(referenceJson, 1, 1, 1, 1, 0)));
        when(osrm.getRouteGeometry(anyList())).thenReturn(List.of());

        RouteGeometryService service = new RouteGeometryService(dayRepository, referenceRepository, osrm);
        RouteGeometryResponse response = service.getRouteGeometries(DATE);

        assertTrue(response.routes().get(0).points().isEmpty());
        verify(osrm).getRouteGeometry(anyList());
    }
}
