package com.dispatch.smartdispatch.dto;

public record TeamResponse(
        Long id,
        String name,
        Integer capacity,
        Double startLatitude,
        Double startLongitude,
        Integer shiftStartMinute,
        Integer shiftEndMinute,
        Boolean active
) { }
