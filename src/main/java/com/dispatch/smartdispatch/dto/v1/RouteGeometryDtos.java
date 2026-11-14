package com.dispatch.smartdispatch.dto.v1;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.util.List;

public final class RouteGeometryDtos {
    private RouteGeometryDtos() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RouteGeometryResponse(
            LocalDate date,
            int planVersion,
            List<RouteGeometryDto> routes
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RouteGeometryDto(
            String staffId,
            List<GeometryPointDto> points
    ) { }

    public record GeometryPointDto(double lat, double lon) { }
}
