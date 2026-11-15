package com.dispatch.smartdispatch.entity.v1;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "v1_reference_snapshot")
public class ReferenceSnapshotEntity {
    @Id
    @Column(name = "snapshot_id")
    private Short snapshotId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "service_center_count", nullable = false)
    private int serviceCenterCount;

    @Column(name = "staff_count", nullable = false)
    private int staffCount;

    @Column(name = "access_node_count", nullable = false)
    private int accessNodeCount;

    @Column(name = "building_count", nullable = false)
    private int buildingCount;

    @Column(name = "work_type_count", nullable = false)
    private int workTypeCount;

    @Column(name = "loaded_at", nullable = false)
    private OffsetDateTime loadedAt;

    protected ReferenceSnapshotEntity() { }

    public ReferenceSnapshotEntity(JsonNode payload, int serviceCenterCount, int staffCount,
                                   int accessNodeCount, int buildingCount, int workTypeCount) {
        this.snapshotId = 1;
        this.payload = payload;
        this.serviceCenterCount = serviceCenterCount;
        this.staffCount = staffCount;
        this.accessNodeCount = accessNodeCount;
        this.buildingCount = buildingCount;
        this.workTypeCount = workTypeCount;
        this.loadedAt = OffsetDateTime.now();
    }

    public JsonNode getPayload() { return payload; }
    public int getServiceCenterCount() { return serviceCenterCount; }
    public int getStaffCount() { return staffCount; }
    public int getAccessNodeCount() { return accessNodeCount; }
    public int getBuildingCount() { return buildingCount; }
    public int getWorkTypeCount() { return workTypeCount; }
}
