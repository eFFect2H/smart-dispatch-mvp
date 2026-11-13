package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.*;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DayInputService {
    private final DispatchDaySnapshotRepository dayRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final ObjectMapper objectMapper;

    public DayInputService(DispatchDaySnapshotRepository dayRepository,
                           ReferenceSnapshotRepository referenceRepository,
                           ObjectMapper objectMapper) {
        this.dayRepository = dayRepository;
        this.referenceRepository = referenceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LoadResult replace(LocalDate pathDate, DayInputRequest request) {
        if (!pathDate.equals(request.date())) {
            throw V1ApiException.badRequest("Path date must match the date field in request body");
        }
        JsonNode reference = referenceRepository.findById((short) 1)
                .map(ReferenceSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "REFERENCE_NOT_LOADED", "Upload reference data before uploading a day"));

        validateOrders(pathDate, request, reference);
        JsonNode payload = objectMapper.valueToTree(request);
        dayRepository.save(new DispatchDaySnapshotEntity(
                pathDate, request.now(), payload, request.orders().size()));

        return new LoadResult("loaded", Map.of(
                "orders", request.orders().size(),
                "absent_staff", request.absentStaffIds() == null ? 0 : request.absentStaffIds().size()
        ));
    }

    private void validateOrders(LocalDate date, DayInputRequest input, JsonNode reference) {
        Set<String> staffIds = stream(reference.path("staff"))
                .stream()
                .map(x -> x.path("staff_id").asText())
                .collect(Collectors.toSet());

        Map<String, WorkTypeDtoView> workTypes = new HashMap<>();
        reference.path("work_types").forEach(x -> workTypes.put(
                x.path("code").asText(),
                new WorkTypeDtoView(x.path("duration_min").asInt(),
                        x.path("max_start_delay_min").isNull()
                                ? null : x.path("max_start_delay_min").asInt(),
                        x.path("deadline_kind").asText())
        ));

        Map<String, JsonNode> buildings = new HashMap<>();
        Map<String, List<String>> accessNodeBuildings = new HashMap<>();
        Map<String, String> accessNodeTechnology = new HashMap<>();
        reference.path("access_nodes").forEach(node -> {
            String nodeId = node.path("access_node_id").asText();
            accessNodeTechnology.put(nodeId, node.path("technology").asText());
            List<String> bIds = new ArrayList<>();
            node.path("buildings").forEach(building -> {
                String id = building.path("building_id").asText();
                buildings.putIfAbsent(id, building);
                bIds.add(id);
            });
            accessNodeBuildings.put(nodeId, bIds);
        });

        Set<String> orderIds = new HashSet<>();
        for (OrderDto order : input.orders()) {
            if (!orderIds.add(order.orderId())) {
                throw V1ApiException.unprocessable("DUPLICATE_ORDER", "Duplicate order_id: " + order.orderId());
            }
            WorkTypeDtoView workType = workTypes.get(order.workTypeCode().name());
            if (workType == null) {
                throw V1ApiException.unprocessable("UNKNOWN_WORK_TYPE", "Unknown work_type_code for " + order.orderId());
            }
            JsonNode building = buildings.get(order.buildingId());
            if (building == null) {
                throw V1ApiException.unprocessable("UNKNOWN_BUILDING", "Unknown building_id for " + order.orderId());
            }
            if (!accessNodeTechnology.containsKey(order.accessNodeId())
                    || !accessNodeBuildings.getOrDefault(order.accessNodeId(), List.of()).contains(order.buildingId())) {
                throw V1ApiException.unprocessable("INVALID_ACCESS_NODE",
                        "access_node_id does not serve building_id for " + order.orderId());
            }
            if (!accessNodeTechnology.get(order.accessNodeId()).equals(order.technology().name())) {
                throw V1ApiException.unprocessable("TECHNOLOGY_MISMATCH",
                        "Order technology does not match access node for " + order.orderId());
            }
            if (!building.path("building_type").asText().equals(order.buildingType().name())) {
                throw V1ApiException.unprocessable("BUILDING_TYPE_MISMATCH",
                        "Order building_type does not match reference building for " + order.orderId());
            }
            if (order.registeredAt().isAfter(input.now())) {
                throw V1ApiException.unprocessable("ORDER_REGISTERED_IN_FUTURE",
                        "registered_at is later than day now for " + order.orderId());
            }
            if (!order.slot().start().toLocalDate().equals(date)) {
                throw V1ApiException.unprocessable("ORDER_SLOT_DATE_MISMATCH",
                        "slot.start must fall on day date for " + order.orderId());
            }
            if (!order.slot().start().isBefore(order.slot().end())
                    || order.latestStart().isBefore(order.slot().start())
                    || order.latestStart().isAfter(order.slot().end())) {
                throw V1ApiException.unprocessable("INVALID_ORDER_WINDOW",
                        "Invalid slot/latest_start for " + order.orderId());
            }

            java.time.OffsetDateTime expectedLatest = workType.maxStartDelayMin() == null
                    ? order.slot().end()
                    : order.slot().start().plusMinutes(workType.maxStartDelayMin());
            if (expectedLatest.isAfter(order.slot().end())) expectedLatest = order.slot().end();
            if (Math.abs(Duration.between(expectedLatest, order.latestStart()).toMillis()) > 1000) {
                throw V1ApiException.unprocessable("INVALID_LATEST_START",
                        "latest_start does not match work_type max_start_delay_min for " + order.orderId());
            }

            if ("SLOT".equals(workType.deadlineKind())) {
                java.time.OffsetDateTime expectedDeadline =
                        order.latestStart().plusMinutes(workType.durationMin());
                if (Math.abs(Duration.between(expectedDeadline, order.deadline()).toMillis()) > 1000) {
                    throw V1ApiException.unprocessable("INVALID_DEADLINE",
                            "deadline for SLOT work type must equal latest_start + duration_min for " + order.orderId());
                }
            }
        }

        Set<String> absent = input.absentStaffIds() == null
                ? Set.of() : new HashSet<>(input.absentStaffIds());
        if (absent.size() != (input.absentStaffIds() == null ? 0 : input.absentStaffIds().size())) {
            throw V1ApiException.unprocessable("DUPLICATE_ABSENT_STAFF", "absent_staff_ids must be unique");
        }
        Set<String> unknownAbsent = new TreeSet<>(absent);
        unknownAbsent.removeAll(staffIds);
        if (!unknownAbsent.isEmpty()) {
            throw V1ApiException.unprocessable("UNKNOWN_STAFF", "Unknown absent_staff_ids: " + unknownAbsent);
        }
    }

    private List<JsonNode> stream(JsonNode array) {
        List<JsonNode> result = new ArrayList<>();
        array.forEach(result::add);
        return result;
    }

    private record WorkTypeDtoView(int durationMin, Integer maxStartDelayMin, String deadlineKind) { }
}
