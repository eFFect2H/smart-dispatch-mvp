package com.dispatch.smartdispatch.dto.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class PlanDtos {
    private PlanDtos() { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanRequest(
            @Min(1) @Max(300) Integer timeLimitSec
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PlanResponse(
            LocalDate date,
            int planVersion,
            OffsetDateTime asOf,
            List<RouteDto> routes,
            List<UnassignedOrderDto> unassigned,
            List<PgpGroupDto> pgpGroups,
            List<CancelledOrderDto> cancelled,
            List<AlertDto> alerts,
            long computationMs
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RouteDto(
            String staffId,
            String serviceCenterId,
            String status,
            List<StopDto> stops
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StopDto(
            int seq,
            String type,
            String locationId,
            OffsetDateTime arrival,
            OffsetDateTime start,
            OffsetDateTime end,
            String orderId,
            Long travelSec,
            Long distanceM,
            String status
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record UnassignedOrderDto(
            String orderId,
            String reasonCode,
            String reasonText,
            String suggestedAction
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PgpGroupDto(
            String groupId,
            String accessNodeId,
            List<String> orderIds,
            OffsetDateTime detectedAt,
            String action,
            String comment
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CancelledOrderDto(
            String orderId,
            OffsetDateTime cancelledAt,
            String eventId
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AlertDto(
            String alertId,
            OffsetDateTime createdAt,
            String type,
            String status,
            String suggestedAction,
            String message,
            OffsetDateTime resolvedAt,
            String orderId,
            String groupId,
            String staffId,
            OffsetDateTime forecastStart,
            Integer latenessMin,
            String suggestedStaffId,
            String proposedSlot,
            Boolean applied
    ) { }
}
