package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.DemoDataDtos.DemoDayResponse;
import com.dispatch.smartdispatch.dto.v1.DemoDataDtos.DemoInfoResponse;
import com.dispatch.smartdispatch.service.v1.DemoDataService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/demo")
public class DemoDataControllerV1 {
    private final DemoDataService demoDataService;

    public DemoDataControllerV1(DemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    @GetMapping
    public DemoInfoResponse getInfo() {
        return demoDataService.getInfo();
    }

    @PostMapping("/days/{date}/initialize")
    public DemoDayResponse initializeDay(@PathVariable LocalDate date) {
        return demoDataService.initializeDay(date);
    }
}
