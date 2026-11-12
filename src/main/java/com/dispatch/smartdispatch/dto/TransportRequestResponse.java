package com.dispatch.smartdispatch.dto;

import com.dispatch.smartdispatch.entity.RequestStatus;
import java.time.LocalDateTime;

public record TransportRequestResponse(
        Long id,
        String title,
        String address,
        Double latitude,
        Double longitude,
        Integer windowStartMinute,
        Integer windowEndMinute,
        Integer serviceDurationMinutes,
        Integer demand,
        RequestStatus status,
        Long assignedTeamId,
        Integer plannedArrivalMinute,
        LocalDateTime createdAt
) { }
