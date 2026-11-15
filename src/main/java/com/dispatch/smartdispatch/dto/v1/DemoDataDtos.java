package com.dispatch.smartdispatch.dto.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.OrderDto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class DemoDataDtos {
    private DemoDataDtos() { }

    public record DemoInfoResponse(
            String city,
            String timezone,
            double centerLat,
            double centerLon,
            LocalDate today,
            OffsetDateTime initialModelNow,
            String datasetVersion,
            int serviceCenterCount,
            int staffCount,
            int accessNodeCount,
            int buildingCount,
            int orderCount
    ) { }

    public record DemoDayResponse(
            String city,
            LocalDate date,
            OffsetDateTime modelNow,
            String datasetVersion,
            String matrixId,
            int staffCount,
            int buildingCount,
            int orderCount,
            List<OrderDto> orders
    ) { }
}
