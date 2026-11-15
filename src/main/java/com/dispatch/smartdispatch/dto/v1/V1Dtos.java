package com.dispatch.smartdispatch.dto.v1;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class V1Dtos {
    private V1Dtos() { }

    public enum Technology { PON, FTTB, XDSL, DOCSIS }
    public enum Profile { INSTALL, LTP3 }
    public enum BuildingType { MKD, PRIVATE }
    public enum AccessNodeType { OLT, SWITCH, DSLAM, CMTS }
    public enum WorkTypeCode {
        INST_PON_MKD, INST_FTTB_MKD, INST_PRIVATE_VLS, ADD_SERVICE,
        SURVEY_PRIVATE, REPAIR, DEGRADATION, DISMANTLE
    }
    public enum DeadlineKind { SLOT, HOURS, WORKING_DAYS }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceDataRequest(
            @NotEmpty List<@Valid ServiceCenterDto> serviceCenters,
            @NotEmpty List<@Valid StaffDto> staff,
            @NotEmpty List<@Valid AccessNodeDto> accessNodes,
            @NotEmpty List<@Valid WorkTypeDto> workTypes
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServiceCenterDto(
            @NotBlank @Pattern(regexp = "^SC-[0-9]{2}$") String serviceCenterId,
            @NotBlank String name,
            @NotBlank String address,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotNull @Valid OpeningHoursDto openingHours,
            @NotNull @Min(0) Integer restockDurationMin
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record OpeningHoursDto(
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime open,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime close
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StaffDto(
            @NotBlank @Pattern(regexp = "^ST-[0-9]{3}$") String staffId,
            String displayName,
            @NotBlank @Pattern(regexp = "^SC-[0-9]{2}$") String serviceCenterId,
            @NotEmpty List<@NotNull Profile> profiles,
            @NotEmpty List<@NotNull Technology> technologies,
            @NotNull Boolean heightPermit,
            @NotNull @Valid ShiftDto shift,
            @NotNull @Valid EquipmentDto equipmentStart,
            @NotNull @Valid EquipmentDto equipmentCapacity,
            @DecimalMin("0.0") Double experienceYears
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ShiftDto(
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime start,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime end,
            @NotNull @Min(0) Integer breakDurationMin,
            @NotNull @Valid BreakWindowDto breakWindow
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record BreakWindowDto(
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime from,
            @NotNull @JsonFormat(pattern = "HH:mm") LocalTime to
    ) { }

    public record EquipmentDto(
            @JsonProperty("ONT") @NotNull @Min(0) Integer ont,
            @JsonProperty("ROUTER") @NotNull @Min(0) Integer router,
            @JsonProperty("STB") @NotNull @Min(0) Integer stb
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessNodeDto(
            @NotBlank @Pattern(regexp = "^AN-[0-9]{4}$") String accessNodeId,
            @NotNull AccessNodeType nodeType,
            @NotNull Technology technology,
            @NotBlank String name,
            String address,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotEmpty List<@Valid BuildingDto> buildings
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BuildingDto(
            @NotBlank @Pattern(regexp = "^B-[0-9]{5}$") String buildingId,
            @NotBlank String address,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotNull BuildingType buildingType,
            @NotNull Boolean overheadLine,
            @Pattern(regexp = "^SC-[0-9]{2}$") String zoneServiceCenterId
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTypeDto(
            @NotNull WorkTypeCode code,
            @NotBlank String name,
            @NotEmpty List<@NotNull Profile> profiles,
            @NotNull @Min(1) Integer durationMin,
            @JsonProperty(value = "max_start_delay_min", required = true) @Min(0) Integer maxStartDelayMin,
            @NotNull DeadlineKind deadlineKind,
            @JsonProperty(value = "deadline_value", required = true) @Min(1) Integer deadlineValue,
            @JsonProperty(value = "repeat_deadline_value", required = true) @Min(1) Integer repeatDeadlineValue,
            @NotNull Boolean pgpRelevant
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TravelMatrixRequest(
            @NotBlank String matrixId,
            @NotEmpty List<@NotBlank String> points,
            @NotEmpty List<@NotEmpty List<@NotNull @Min(0) Long>> durationsSec,
            @NotEmpty List<@NotEmpty List<@NotNull @Min(0) Long>> distancesM,
            List<@Valid HourlyFactorDto> hourlyFactors
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record HourlyFactorDto(
            @NotNull @Min(0) @Max(23) Integer hour,
            @NotNull @DecimalMin("0.0") Double factor
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DayInputRequest(
            @NotNull LocalDate date,
            @NotNull OffsetDateTime now,
            @NotNull List<@Valid OrderDto> orders,
            List<@NotBlank String> absentStaffIds
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderDto(
            @NotBlank @Pattern(regexp = "^ORD-[0-9]{8}-[0-9]{4}$") String orderId,
            @NotNull WorkTypeCode workTypeCode,
            @NotNull Technology technology,
            @NotBlank @Pattern(regexp = "^B-[0-9]{5}$") String buildingId,
            @NotBlank String address,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
            @NotNull BuildingType buildingType,
            @NotNull Boolean requiresHeight,
            @NotBlank @Pattern(regexp = "^AN-[0-9]{4}$") String accessNodeId,
            @NotNull OffsetDateTime registeredAt,
            @NotNull @Valid TimeWindowDto slot,
            @NotNull OffsetDateTime latestStart,
            @NotNull OffsetDateTime deadline,
            @NotNull @Valid EquipmentDto requiredEquipment,
            @NotNull Boolean isRepeat,
            String previousOrderId
    ) { }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TimeWindowDto(
            @NotNull OffsetDateTime start,
            @NotNull OffsetDateTime end
    ) { }

    public record HealthResponse(String status, String team, String version) { }
    public record LoadResult(String status, Map<String, Integer> counts) { }
    public record ErrorResponse(ErrorBody error) { }
    public record ErrorBody(String code, String message, List<ErrorDetail> details) { }
    public record ErrorDetail(String field, String message) { }
}
