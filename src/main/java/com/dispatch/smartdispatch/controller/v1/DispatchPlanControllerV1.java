package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.PlanRequest;
import com.dispatch.smartdispatch.dto.v1.PlanDtos.PlanResponse;
import com.dispatch.smartdispatch.service.v1.DispatchPlanService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/days/{date}/plan")
public class DispatchPlanControllerV1 {
    private final DispatchPlanService planService;

    public DispatchPlanControllerV1(DispatchPlanService planService) {
        this.planService = planService;
    }

    @PostMapping
    public PlanResponse generate(@PathVariable LocalDate date,
                                 @Valid @RequestBody(required = false) PlanRequest request) {
        return planService.generate(date, request);
    }

    @GetMapping
    public JsonNode getLatest(@PathVariable LocalDate date) {
        return planService.getLatest(date);
    }
}
