package com.dispatch.smartdispatch.entity.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.EventType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "v1_dispatch_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_v1_dispatch_events_day_event_id",
                columnNames = {"day_date", "event_id"}
        )
)
public class DispatchEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_sequence")
    private Long eventSequence;

    @Column(name = "day_date", nullable = false)
    private LocalDate dayDate;

    @Column(name = "event_id", nullable = false, length = 128)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "staff_id", length = 64)
    private String staffId;

    @Column(name = "target_staff_id", length = 64)
    private String targetStaffId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "received_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime receivedAt;

    protected DispatchEventEntity() { }

    public DispatchEventEntity(LocalDate dayDate, String eventId, EventType eventType,
                               OffsetDateTime occurredAt, String orderId, String staffId,
                               String targetStaffId, JsonNode payload) {
        this.dayDate = dayDate;
        this.eventId = eventId;
        this.eventType = eventType;
        this.occurredAt = occurredAt;
        this.orderId = orderId;
        this.staffId = staffId;
        this.targetStaffId = targetStaffId;
        this.payload = payload;
    }

    public Long getEventSequence() { return eventSequence; }
    public LocalDate getDayDate() { return dayDate; }
    public String getEventId() { return eventId; }
    public EventType getEventType() { return eventType; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getOrderId() { return orderId; }
    public String getStaffId() { return staffId; }
    public String getTargetStaffId() { return targetStaffId; }
    public JsonNode getPayload() { return payload; }
    public OffsetDateTime getReceivedAt() { return receivedAt; }
}
