package com.dispatch.smartdispatch.dto;

import jakarta.validation.constraints.*;

public record TeamCreateRequest(
        @NotBlank @Size(max = 150) String name,
        @NotNull @Min(1) @Max(1000) Integer capacity,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double startLatitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double startLongitude,
        @NotNull @Min(0) @Max(1439) Integer shiftStartMinute,
        @NotNull @Min(1) @Max(1440) Integer shiftEndMinute,
        Boolean active
) {
    public TeamCreateRequest {
        if (active == null) active = true;
    }
}
