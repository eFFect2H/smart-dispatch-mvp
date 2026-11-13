package com.dispatch.smartdispatch.entity.v1;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "v1_travel_matrix_snapshot")
public class TravelMatrixSnapshotEntity {
    @Id
    @Column(name = "snapshot_id")
    private Short snapshotId;

    @Column(name = "matrix_id", nullable = false, length = 100)
    private String matrixId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "point_count", nullable = false)
    private int pointCount;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;

    protected TravelMatrixSnapshotEntity() { }

    public TravelMatrixSnapshotEntity(String matrixId, JsonNode payload, int pointCount) {
        this.snapshotId = 1;
        this.matrixId = matrixId;
        this.payload = payload;
        this.pointCount = pointCount;
        this.uploadedAt = OffsetDateTime.now();
    }

    public String getMatrixId() { return matrixId; }
    public JsonNode getPayload() { return payload; }
    public int getPointCount() { return pointCount; }
}
