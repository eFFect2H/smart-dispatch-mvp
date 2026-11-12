package com.dispatch.smartdispatch.controller;

import com.dispatch.smartdispatch.dto.DispatchPlanResponse;
import com.dispatch.smartdispatch.service.DispatchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {
    private final DispatchService service;

    public DispatchController(DispatchService service) {
        this.service = service;
    }

    @PostMapping("/optimize")
    public DispatchPlanResponse optimize() {
        return service.optimize();
    }
}
