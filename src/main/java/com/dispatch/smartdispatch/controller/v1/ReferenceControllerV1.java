package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.LoadResult;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.ReferenceDataRequest;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.TravelMatrixRequest;
import com.dispatch.smartdispatch.service.v1.ReferenceDataService;
import com.dispatch.smartdispatch.service.v1.TravelMatrixService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/reference")
public class ReferenceControllerV1 {
    private final ReferenceDataService referenceService;
    private final TravelMatrixService matrixService;

    public ReferenceControllerV1(ReferenceDataService referenceService,
                                 TravelMatrixService matrixService) {
        this.referenceService = referenceService;
        this.matrixService = matrixService;
    }

    @PutMapping
    public LoadResult replaceReference(@Valid @RequestBody ReferenceDataRequest request) {
        return referenceService.replace(request);
    }

    @PutMapping("/travel-matrix")
    public LoadResult replaceTravelMatrix(@Valid @RequestBody TravelMatrixRequest request) {
        return matrixService.replace(request);
    }
}
