package com.dispatch.smartdispatch.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "teams")
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "start_latitude", nullable = false)
    private Double startLatitude;

    @Column(name = "start_longitude", nullable = false)
    private Double startLongitude;

    @Column(name = "shift_start_minute", nullable = false)
    private Integer shiftStartMinute;

    @Column(name = "shift_end_minute", nullable = false)
    private Integer shiftEndMinute;

    @Column(nullable = false)
    private Boolean active = true;

    public Team() { }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public Double getStartLatitude() { return startLatitude; }
    public void setStartLatitude(Double startLatitude) { this.startLatitude = startLatitude; }
    public Double getStartLongitude() { return startLongitude; }
    public void setStartLongitude(Double startLongitude) { this.startLongitude = startLongitude; }
    public Integer getShiftStartMinute() { return shiftStartMinute; }
    public void setShiftStartMinute(Integer shiftStartMinute) { this.shiftStartMinute = shiftStartMinute; }
    public Integer getShiftEndMinute() { return shiftEndMinute; }
    public void setShiftEndMinute(Integer shiftEndMinute) { this.shiftEndMinute = shiftEndMinute; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}
