package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.*;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ReferenceDataService {
    private final ReferenceSnapshotRepository referenceRepository;
    private final DispatchDaySnapshotRepository dayRepository;
    private final ReferenceValidationService validator;
    private final ObjectMapper objectMapper;

    public ReferenceDataService(ReferenceSnapshotRepository referenceRepository,
                                DispatchDaySnapshotRepository dayRepository,
                                ReferenceValidationService validator,
                                ObjectMapper objectMapper) {
        this.referenceRepository = referenceRepository;
        this.dayRepository = dayRepository;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoadResult replace(ReferenceDataRequest request) {
        validator.validate(request);
        JsonNode payload = toJson(request);
        int buildings = (int) request.accessNodes().stream()
                .flatMap(node -> node.buildings().stream())
                .map(BuildingDto::buildingId).distinct().count();

        referenceRepository.save(new ReferenceSnapshotEntity(
                payload,
                request.serviceCenters().size(),
                request.staff().size(),
                request.accessNodes().size(),
                buildings,
                request.workTypes().size()
        ));

        // A new reference version invalidates all previously uploaded day state.
        dayRepository.deleteAllInBatch();

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("service_centers", request.serviceCenters().size());
        counts.put("staff", request.staff().size());
        counts.put("access_nodes", request.accessNodes().size());
        counts.put("buildings", buildings);
        counts.put("work_types", request.workTypes().size());
        return new LoadResult("loaded", counts);
    }

    @Transactional(readOnly = true)
    public JsonNode getPayloadOrThrow() {
        return referenceRepository.findById((short) 1)
                .map(ReferenceSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "REFERENCE_NOT_LOADED", "Reference data has not been uploaded"));
    }

    private JsonNode toJson(Object value) {
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Could not serialize reference data", ex);
        }
    }
}
