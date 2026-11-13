package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.EventType;
import com.dispatch.smartdispatch.dto.v1.PlanDtos.CancelledOrderDto;
import com.dispatch.smartdispatch.entity.v1.DispatchEventEntity;
import com.dispatch.smartdispatch.repository.v1.DispatchEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Reconstructs the effective planning input by replaying the append-only event log
 * over the original day snapshot. The source snapshot and event journal are never mutated.
 */
@Service
public class DispatchEventReplayService {
    private final DispatchEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    public DispatchEventReplayService(DispatchEventRepository eventRepository,
                                      ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    public ReplayResult replay(LocalDate date, JsonNode originalDayPayload,
                               Set<String> frozenOrderIds) {
        ObjectNode effective = (ObjectNode) originalDayPayload.deepCopy();
        ArrayNode orders = arrayOrCreate(effective, "orders");
        Map<String, ObjectNode> ordersById = new LinkedHashMap<>();
        for (JsonNode node : orders) {
            if (node.isObject() && node.hasNonNull("order_id")) {
                ordersById.put(node.path("order_id").asText(), (ObjectNode) node);
            }
        }

        ArrayNode absentStaff = arrayOrCreate(effective, "absent_staff_ids");
        Set<String> absentStaffIds = new LinkedHashSet<>();
        absentStaff.forEach(node -> absentStaffIds.add(node.asText()));

        Map<String, CancelledOrderDto> cancelled = new LinkedHashMap<>();
        Map<String, String> transferTargets = new LinkedHashMap<>();

        // Repository order is event_sequence order: later events intentionally override
        // earlier projections (for example, a second delay or a subsequent transfer).
        List<DispatchEventEntity> events =
                eventRepository.findAllByDayDateOrderByEventSequenceAsc(date);
        for (DispatchEventEntity event : events) {
            String orderId = event.getOrderId();
            boolean frozen = orderId != null && frozenOrderIds.contains(orderId);

            switch (event.getEventType()) {
                case NEW_ORDER -> appendNewOrder(event, orders, ordersById);

                case ORDER_CANCELLED -> {
                    if (orderId != null && !frozen) {
                        cancelled.putIfAbsent(orderId, new CancelledOrderDto(
                                orderId, event.getOccurredAt(), event.getEventId()));
                        transferTargets.remove(orderId);
                    }
                }

                case VISIT_DELAYED -> {
                    if (orderId != null && !frozen && !cancelled.containsKey(orderId)) {
                        ObjectNode order = ordersById.get(orderId);
                        if (order != null) {
                            int delayMin = event.getPayload().path("delay_min").asInt();
                            shiftOrderWindow(order, delayMin);
                        }
                    }
                }

                case STAFF_UNAVAILABLE -> {
                    String staffId = event.getStaffId();
                    if (staffId != null && !staffId.isBlank()) {
                        absentStaffIds.add(staffId);
                    }
                }

                case ORDER_TRANSFERRED -> {
                    if (orderId != null && !frozen && !cancelled.containsKey(orderId)
                            && event.getTargetStaffId() != null) {
                        transferTargets.put(orderId, event.getTargetStaffId());
                    }
                }
            }
        }

        effective.set("absent_staff_ids", objectMapper.valueToTree(absentStaffIds));
        return new ReplayResult(
                effective,
                Set.copyOf(cancelled.keySet()),
                List.copyOf(cancelled.values()),
                Map.copyOf(transferTargets)
        );
    }

    private ArrayNode arrayOrCreate(ObjectNode parent, String field) {
        JsonNode value = parent.path(field);
        if (value.isArray()) {
            return (ArrayNode) value;
        }
        ArrayNode created = objectMapper.createArrayNode();
        parent.set(field, created);
        return created;
    }

    private void appendNewOrder(DispatchEventEntity event, ArrayNode orders,
                                Map<String, ObjectNode> ordersById) {
        JsonNode payloadOrder = event.getPayload().path("order");
        if (!payloadOrder.isObject()) {
            throw new IllegalStateException(
                    "Stored NEW_ORDER event " + event.getEventId() + " has no order object");
        }
        ObjectNode order = (ObjectNode) payloadOrder.deepCopy();
        String orderId = order.path("order_id").asText();
        if (orderId.isBlank()) {
            throw new IllegalStateException(
                    "Stored NEW_ORDER event " + event.getEventId() + " has no order_id");
        }
        if (ordersById.putIfAbsent(orderId, order) == null) {
            orders.add(order);
        }
    }

    private void shiftOrderWindow(ObjectNode order, int delayMin) {
        shiftNested(order, "slot", "start", delayMin);
        shiftNested(order, "slot", "end", delayMin);
        shiftField(order, "latest_start", delayMin);
        shiftField(order, "deadline", delayMin);
    }

    private void shiftNested(ObjectNode order, String parent, String field, int delayMin) {
        JsonNode nested = order.path(parent);
        if (!nested.isObject() || !nested.hasNonNull(field)) {
            throw new IllegalStateException("Stored order is missing " + parent + "." + field);
        }
        ObjectNode nestedObject = (ObjectNode) nested;
        nestedObject.put(field, OffsetDateTime.parse(nested.path(field).asText())
                .plusMinutes(delayMin).toString());
    }

    private void shiftField(ObjectNode order, String field, int delayMin) {
        if (!order.hasNonNull(field)) {
            throw new IllegalStateException("Stored order is missing " + field);
        }
        order.put(field, OffsetDateTime.parse(order.path(field).asText())
                .plusMinutes(delayMin).toString());
    }

    public record ReplayResult(
            JsonNode effectiveDayPayload,
            Set<String> cancelledOrderIds,
            List<CancelledOrderDto> cancelledOrders,
            Map<String, String> transferTargets
    ) { }
}
