package com.dispatch.smartdispatch.entity.v1;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "v1_demo_dataset")
public class DemoDatasetEntity {
    @Id
    @Column(name = "dataset_id")
    private Short datasetId;

    @Column(name = "city_name", nullable = false, length = 120)
    private String cityName;

    @Column(name = "timezone_id", nullable = false, length = 80)
    private String timezoneId;

    @Column(name = "center_lat", nullable = false)
    private double centerLat;

    @Column(name = "center_lon", nullable = false)
    private double centerLon;

    @Column(name = "dataset_version", nullable = false, length = 80)
    private String datasetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "orders_payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode ordersPayload;

    @Column(name = "order_count", nullable = false)
    private int orderCount;

    protected DemoDatasetEntity() { }

    public String getCityName() { return cityName; }
    public String getTimezoneId() { return timezoneId; }
    public double getCenterLat() { return centerLat; }
    public double getCenterLon() { return centerLon; }
    public String getDatasetVersion() { return datasetVersion; }
    public JsonNode getOrdersPayload() { return ordersPayload; }
    public int getOrderCount() { return orderCount; }
}
