package com.dispatch.smartdispatch.entity.v1;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "v1_dispatch_days")
public class DispatchDaySnapshotEntity {
    @Id
    @Column(name = "day_date")
    private LocalDate dayDate;

    @Column(name = "model_now", nullable = false)
    private OffsetDateTime modelNow;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    @Column(name = "loaded_at", nullable = false)
    private OffsetDateTime loadedAt;

    @Column(name = "plan_version", nullable = false)
    private int planVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_payload", columnDefinition = "jsonb")
    private JsonNode planPayload;

    @Column(name = "plan_as_of")
    private OffsetDateTime planAsOf;

    protected DispatchDaySnapshotEntity() { }

    public DispatchDaySnapshotEntity(LocalDate dayDate, OffsetDateTime modelNow,
                                     JsonNode payload, int orderCount) {
        this.dayDate = dayDate;
        this.modelNow = modelNow;
        this.payload = payload;
        this.orderCount = orderCount;
        this.loadedAt = OffsetDateTime.now();
        this.planVersion = 0;
    }

    public void updatePlan(int version, JsonNode plan, OffsetDateTime asOf) {
        this.planVersion = version;
        this.planPayload = plan;
        this.planAsOf = asOf;
    }

    public LocalDate getDayDate() { return dayDate; }
    public OffsetDateTime getModelNow() { return modelNow; }
    public JsonNode getPayload() { return payload; }
    public int getOrderCount() { return orderCount; }
    public int getPlanVersion() { return planVersion; }
    public JsonNode getPlanPayload() { return planPayload; }
    public OffsetDateTime getPlanAsOf() { return planAsOf; }
}
