package com.dispatch.smartdispatch.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "transport_requests")
public class TransportRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "window_start_minute", nullable = false)
    private Integer windowStartMinute;

    @Column(name = "window_end_minute", nullable = false)
    private Integer windowEndMinute;

    @Column(name = "service_duration_minutes", nullable = false)
    private Integer serviceDurationMinutes;

    @Column(nullable = false)
    private Integer demand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequestStatus status = RequestStatus.NEW;

    @Column(name = "assigned_team_id")
    private Long assignedTeamId;

    @Column(name = "planned_arrival_minute")
    private Integer plannedArrivalMinute;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = RequestStatus.NEW;
    }

    public TransportRequest() { }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Integer getWindowStartMinute() { return windowStartMinute; }
    public void setWindowStartMinute(Integer windowStartMinute) { this.windowStartMinute = windowStartMinute; }
    public Integer getWindowEndMinute() { return windowEndMinute; }
    public void setWindowEndMinute(Integer windowEndMinute) { this.windowEndMinute = windowEndMinute; }
    public Integer getServiceDurationMinutes() { return serviceDurationMinutes; }
    public void setServiceDurationMinutes(Integer serviceDurationMinutes) { this.serviceDurationMinutes = serviceDurationMinutes; }
    public Integer getDemand() { return demand; }
    public void setDemand(Integer demand) { this.demand = demand; }
    public RequestStatus getStatus() { return status; }
    public void setStatus(RequestStatus status) { this.status = status; }
    public Long getAssignedTeamId() { return assignedTeamId; }
    public void setAssignedTeamId(Long assignedTeamId) { this.assignedTeamId = assignedTeamId; }
    public Integer getPlannedArrivalMinute() { return plannedArrivalMinute; }
    public void setPlannedArrivalMinute(Integer plannedArrivalMinute) { this.plannedArrivalMinute = plannedArrivalMinute; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
