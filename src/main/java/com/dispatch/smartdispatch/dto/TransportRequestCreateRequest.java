package com.dispatch.smartdispatch.dto;

import jakarta.validation.constraints.*;

public record TransportRequestCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 500) String address,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
        @NotNull @Min(0) @Max(1439) Integer windowStartMinute,
        @NotNull @Min(1) @Max(1440) Integer windowEndMinute,
        @NotNull @Min(0) @Max(1440) Integer serviceDurationMinutes,
        @NotNull @Min(1) @Max(1000) Integer demand
) { }
