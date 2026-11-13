package com.dispatch.smartdispatch.dto.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class EventDtos {
    private EventDtos() { }

    public enum EventType {
        NEW_ORDER, ORDER_CANCELLED, VISIT_DELAYED, STAFF_UNAVAILABLE, ORDER_TRANSFERRED
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventRequest(
            @NotBlank @Size(max = 128) String eventId,
            @NotNull EventType eventType,
            @NotNull OffsetDateTime occurredAt,
            @Size(max = 64) String orderId,
            @Size(max = 64) String staffId,
            @Size(max = 64) String targetStaffId,
            JsonNode payload
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventAcceptedResponse(
            String status, String eventId, boolean duplicate, OffsetDateTime modelNow
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record EventHistoryResponse(
            LocalDate date, OffsetDateTime modelNow, List<EventView> events
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EventView(
            Long eventSequence,
            String eventId,
            EventType eventType,
            OffsetDateTime occurredAt,
            String orderId,
            String staffId,
            String targetStaffId,
            JsonNode payload,
            OffsetDateTime receivedAt
    ) { }
}
