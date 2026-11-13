package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.*;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ReferenceValidationService {
    public void validate(ReferenceDataRequest request) {
        Set<String> centerIds = unique(request.serviceCenters().stream()
                .map(ServiceCenterDto::serviceCenterId).toList(), "service_centers.service_center_id");
        unique(request.staff().stream().map(StaffDto::staffId).toList(), "staff.staff_id");
        unique(request.accessNodes().stream().map(AccessNodeDto::accessNodeId).toList(), "access_nodes.access_node_id");
        unique(request.workTypes().stream().map(x -> x.code().name()).toList(), "work_types.code");

        for (ServiceCenterDto center : request.serviceCenters()) {
            require(center.openingHours().open().isBefore(center.openingHours().close()),
                    "service_center " + center.serviceCenterId() + " opening_hours.open must be before close");
        }

        for (StaffDto staff : request.staff()) {
            require(centerIds.contains(staff.serviceCenterId()),
                    "staff." + staff.staffId() + ".service_center_id references an unknown service center");
            require(staff.shift().start().isBefore(staff.shift().end()),
                    "staff." + staff.staffId() + ".shift must start before it ends");
            require(!staff.shift().breakWindow().from().isAfter(staff.shift().breakWindow().to()),
                    "staff." + staff.staffId() + ".shift.break_window.from must not be after to");
            validateUnique(staff.profiles(), "staff." + staff.staffId() + ".profiles");
            validateUnique(staff.technologies(), "staff." + staff.staffId() + ".technologies");
            validateEquipment(staff.equipmentStart(), "staff." + staff.staffId() + ".equipment_start");
            validateEquipment(staff.equipmentCapacity(), "staff." + staff.staffId() + ".equipment_capacity");
            require(staff.equipmentCapacity().ont() >= staff.equipmentStart().ont()
                            && staff.equipmentCapacity().router() >= staff.equipmentStart().router()
                            && staff.equipmentCapacity().stb() >= staff.equipmentStart().stb(),
                    "staff." + staff.staffId() + ".equipment_capacity must be >= equipment_start");
        }

        Map<String, BuildingDto> buildingsById = new HashMap<>();
        for (AccessNodeDto node : request.accessNodes()) {
            require(node.technology() == technologyFor(node.nodeType()),
                    "access_node " + node.accessNodeId() + " node_type and technology do not match");
            unique(node.buildings().stream().map(BuildingDto::buildingId).toList(),
                    "access_nodes." + node.accessNodeId() + ".buildings.building_id");
            for (BuildingDto building : node.buildings()) {
                if (building.zoneServiceCenterId() != null) {
                    require(centerIds.contains(building.zoneServiceCenterId()),
                            "building " + building.buildingId() + " references an unknown zone service center");
                }
                BuildingDto previous = buildingsById.putIfAbsent(building.buildingId(), building);
                if (previous != null) {
                    require(sameBuilding(previous, building),
                            "building " + building.buildingId() + " has conflicting data in access_nodes");
                }
            }
        }

        for (WorkTypeDto workType : request.workTypes()) {
            validateUnique(workType.profiles(), "work_types." + workType.code() + ".profiles");
            if (workType.deadlineKind() == DeadlineKind.SLOT) {
                require(workType.deadlineValue() == null,
                        "work_types." + workType.code() + ".deadline_value must be null for SLOT");
            } else {
                require(workType.deadlineValue() != null,
                        "work_types." + workType.code() + ".deadline_value is required for non-SLOT deadlines");
            }
        }
    }

    private Technology technologyFor(AccessNodeType type) {
        return switch (type) {
            case OLT -> Technology.PON;
            case SWITCH -> Technology.FTTB;
            case DSLAM -> Technology.XDSL;
            case CMTS -> Technology.DOCSIS;
        };
    }

    private boolean sameBuilding(BuildingDto a, BuildingDto b) {
        return a.address().equals(b.address())
                && Double.compare(a.lat(), b.lat()) == 0
                && Double.compare(a.lon(), b.lon()) == 0
                && a.buildingType() == b.buildingType()
                && a.overheadLine().equals(b.overheadLine())
                && Objects.equals(a.zoneServiceCenterId(), b.zoneServiceCenterId());
    }

    private void validateEquipment(EquipmentDto e, String field) {
        require(e.ont() >= 0 && e.router() >= 0 && e.stb() >= 0, field + " values must be non-negative");
    }

    private <T> void validateUnique(List<T> values, String field) {
        require(values.size() == new HashSet<>(values).size(), field + " must not contain duplicates");
    }

    private Set<String> unique(List<String> values, String field) {
        validateUnique(values, field);
        return new HashSet<>(values);
    }

    private void require(boolean condition, String message) {
        if (!condition) throw V1ApiException.unprocessable("INVALID_REFERENCE", message);
    }
}
