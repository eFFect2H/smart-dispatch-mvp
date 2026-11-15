package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.*;
import com.dispatch.smartdispatch.entity.v1.TravelMatrixSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.TravelMatrixSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class TravelMatrixService {
    private final TravelMatrixSnapshotRepository matrixRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final ObjectMapper objectMapper;

    public TravelMatrixService(TravelMatrixSnapshotRepository matrixRepository,
                               ReferenceSnapshotRepository referenceRepository,
                               ObjectMapper objectMapper) {
        this.matrixRepository = matrixRepository;
        this.referenceRepository = referenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoadResult replace(TravelMatrixRequest request) {
        validateShape(request);
        referenceRepository.findById((short) 1).ifPresent(reference -> {
            Set<String> expected = extractExpectedPoints(reference.getPayload());
            if (!expected.equals(new HashSet<>(request.points()))) {
                Set<String> missing = new TreeSet<>(expected);
                missing.removeAll(request.points());
                Set<String> unknown = new TreeSet<>(request.points());
                unknown.removeAll(expected);
                throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX",
                        "Matrix points must contain all and only service centers and buildings. Missing="
                                + missing + ", unknown=" + unknown);
            }
        });

        JsonNode payload = objectMapper.valueToTree(request);
        matrixRepository.save(new TravelMatrixSnapshotEntity(
                request.matrixId(), payload, request.points().size()));

        return new LoadResult("loaded", Map.of("points", request.points().size()));
    }

    private void validateShape(TravelMatrixRequest request) {
        int n = request.points().size();
        if (n < 2) throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX", "At least two matrix points are required");
        if (new HashSet<>(request.points()).size() != n) {
            throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX", "Matrix points must be unique");
        }
        if (request.durationsSec().size() != n || request.distancesM().size() != n) {
            throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX", "Both matrices must have N rows");
        }
        for (int i = 0; i < n; i++) {
            List<Long> durationRow = request.durationsSec().get(i);
            List<Long> distanceRow = request.distancesM().get(i);
            if (durationRow.size() != n || distanceRow.size() != n) {
                throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX",
                        "Matrix row " + i + " must contain exactly N values");
            }
            if (durationRow.get(i) != 0L || distanceRow.get(i) != 0L) {
                throw V1ApiException.unprocessable("INVALID_TRAVEL_MATRIX",
                        "Diagonal values must be zero (row " + i + ")");
            }
        }
    }

    private Set<String> extractExpectedPoints(JsonNode reference) {
        Set<String> ids = new HashSet<>();
        reference.path("service_centers")
                .forEach(node -> ids.add(node.path("service_center_id").asText()));
        reference.path("access_nodes").forEach(node -> node.path("buildings").forEach(
                building -> ids.add(building.path("building_id").asText())));
        return ids;
    }
}
