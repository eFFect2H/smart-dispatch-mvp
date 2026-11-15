package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.RouteGeometryDtos.RouteGeometryResponse;
import com.dispatch.smartdispatch.service.v1.RouteGeometryService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/days/{date}/plan")
public class RouteGeometryControllerV1 {
    private final RouteGeometryService routeGeometryService;

    public RouteGeometryControllerV1(RouteGeometryService routeGeometryService) {
        this.routeGeometryService = routeGeometryService;
    }

    @GetMapping("/geometry")
    public RouteGeometryResponse getGeometry(@PathVariable LocalDate date) {
        return routeGeometryService.getRouteGeometries(date);
    }
}
