package com.dispatch.smartdispatch.dto;

import java.util.List;

public record DispatchPlanResponse(
        int assignedRequests,
        int totalTravelMinutes,
        List<RoutePlan> routes
) {
    public record RoutePlan(
            Long teamId,
            String teamName,
            double baseLatitude,
            double baseLongitude,
            int shiftStartMinute,
            int finishMinute,
            int totalTravelMinutes,
            List<StopPlan> stops,
            List<RoutePoint> geometry
    ) {
    }

    public record StopPlan(
            Long requestId,
            String title,
            String address,
            double latitude,
            double longitude,
            int arrivalMinute,
            int travelFromPreviousMinutes,
            int serviceDurationMinutes
    ) {
    }

    public record RoutePoint(
            double latitude,
            double longitude
    ) {
    }
}