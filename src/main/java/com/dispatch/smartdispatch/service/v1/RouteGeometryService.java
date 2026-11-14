package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.RouteGeometryDtos.GeometryPointDto;
import com.dispatch.smartdispatch.dto.v1.RouteGeometryDtos.RouteGeometryDto;
import com.dispatch.smartdispatch.dto.v1.RouteGeometryDtos.RouteGeometryResponse;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.routing.Coordinate;
import com.dispatch.smartdispatch.routing.OsrmMatrixClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RouteGeometryService {
    private final DispatchDaySnapshotRepository dayRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final OsrmMatrixClient osrmMatrixClient;

    public RouteGeometryService(DispatchDaySnapshotRepository dayRepository,
                                 ReferenceSnapshotRepository referenceRepository,
                                 OsrmMatrixClient osrmMatrixClient) {
        this.dayRepository = dayRepository;
        this.referenceRepository = referenceRepository;
        this.osrmMatrixClient = osrmMatrixClient;
    }

    /**
     * Builds display-only road geometry for the ordered stops in the persisted plan.
     * The optimizer continues to use the saved duration/distance matrix; this endpoint
     * calls OSRM Route only to obtain a road-following line for the map.
     */
    @Transactional(readOnly = true)
    public RouteGeometryResponse getRouteGeometries(LocalDate date) {
        DispatchDaySnapshotEntity day = dayRepository.findById(date)
                .orElseThrow(() -> V1ApiException.conflict(
                        "DAY_NOT_LOADED", "Day input has not been initialized"));

        JsonNode plan = day.getPlanPayload();
        if (plan == null) {
            throw V1ApiException.conflict("PLAN_NOT_GENERATED",
                    "Generate a plan before requesting road route geometries");
        }

        JsonNode reference = referenceRepository.findById((short) 1)
                .map(ReferenceSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "REFERENCE_NOT_LOADED", "Reference data has not been uploaded"));

        Map<String, Coordinate> locations = indexLocations(reference);
        List<RouteGeometryDto> routes = new ArrayList<>();
        JsonNode routeNodes = plan.path("routes");
        for (JsonNode route : routeNodes) {
            String staffId = route.path("staff_id").asText();
            List<Coordinate> waypoints = collectWaypoints(route.path("stops"), locations, staffId);
            List<Coordinate> geometry = waypoints.size() < 2
                    ? List.of()
                    : osrmMatrixClient.getRouteGeometry(waypoints);

            List<GeometryPointDto> points = geometry.stream()
                    .map(point -> new GeometryPointDto(point.latitude(), point.longitude()))
                    .toList();
            routes.add(new RouteGeometryDto(staffId, points));
        }

        return new RouteGeometryResponse(date, day.getPlanVersion(), List.copyOf(routes));
    }

    private Map<String, Coordinate> indexLocations(JsonNode reference) {
        Map<String, Coordinate> result = new HashMap<>();
        reference.path("service_centers").forEach(center ->
                result.put(center.path("service_center_id").asText(), coordinate(center)));
        reference.path("access_nodes").forEach(node ->
                node.path("buildings").forEach(building ->
                        result.put(building.path("building_id").asText(), coordinate(building))));
        return result;
    }

    private Coordinate coordinate(JsonNode location) {
        return new Coordinate(location.path("lon").asDouble(), location.path("lat").asDouble());
    }

    private List<Coordinate> collectWaypoints(JsonNode stops,
                                              Map<String, Coordinate> locations,
                                              String staffId) {
        List<Coordinate> result = new ArrayList<>();
        for (JsonNode stop : stops) {
            String locationId = stop.path("location_id").asText();
            Coordinate coordinate = locations.get(locationId);
            if (coordinate == null) {
                throw V1ApiException.conflict("ROUTE_LOCATION_NOT_FOUND",
                        "Cannot resolve " + locationId + " from the reference data for staff " + staffId);
            }
            // Break/restock stops can stay at the previous location. Avoid sending
            // duplicate consecutive coordinates to OSRM, but preserve revisits later in the route.
            if (result.isEmpty() || !samePoint(result.get(result.size() - 1), coordinate)) {
                result.add(coordinate);
            }
        }
        return result;
    }

    private boolean samePoint(Coordinate first, Coordinate second) {
        return Double.compare(first.longitude(), second.longitude()) == 0
                && Double.compare(first.latitude(), second.latitude()) == 0;
    }
}
