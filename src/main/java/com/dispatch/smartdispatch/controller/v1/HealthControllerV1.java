package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
public class HealthControllerV1 {
    private final String team;
    private final String version;

    public HealthControllerV1(
            @Value("${app.team:smart-dispatch}") String team,
            @Value("${app.version:0.1.0}") String version) {
        this.team = team;
        this.version = version;
    }

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("ok", team, version);
    }
}
