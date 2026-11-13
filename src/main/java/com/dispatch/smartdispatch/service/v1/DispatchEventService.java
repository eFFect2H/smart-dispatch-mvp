package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.*;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.OrderDto;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.DispatchEventEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.DispatchEventRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DispatchEventService {
    private final DispatchDaySnapshotRepository dayRepository;
    private final DispatchEventRepository eventRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public DispatchEventService(DispatchDaySnapshotRepository dayRepository,
                                DispatchEventRepository eventRepository,
                                ReferenceSnapshotRepository referenceRepository,
                                ObjectMapper objectMapper,
                                Validator validator) {
        this.dayRepository = dayRepository;
        this.eventRepository = eventRepository;
        this.referenceRepository = referenceRepository;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    @Transactional
    public EventAcceptedResponse append(LocalDate date, EventRequest request) {
        DispatchDaySnapshotEntity day = dayRepository.findByDateForUpdate(date)
                .orElseThrow(() -> V1ApiException.conflict(
                        "DAY_NOT_LOADED", "Upload day input before appending events"));

        JsonNode payload = normalizePayload(request.payload());
        Optional<DispatchEventEntity> existing = eventRepository
                .findByDayDateAndEventId(date, request.eventId());
        if (existing.isPresent()) {
            if (!hasSameContent(existing.get(), request, payload)) {
                throw V1ApiException.conflict(
                        "EVENT_ID_CONFLICT", "event_id has already been used with different event content");
            }
            return new EventAcceptedResponse(
                    "ALREADY_RECORDED", request.eventId(), true, day.getModelNow());
        }

        if (!request.occurredAt().toLocalDate().equals(date)) {
            throw V1ApiException.unprocessable(
                    "EVENT_DATE_MISMATCH", "occurred_at must fall on the date in the request path");
        }
        if (request.occurredAt().isBefore(day.getModelNow())) {
            throw V1ApiException.unprocessable(
                    "EVENT_TIME_REGRESSION", "occurred_at cannot be earlier than the current model time");
        }

        List<DispatchEventEntity> history =
                eventRepository.findAllByDayDateOrderByEventSequenceAsc(date);
        JsonNode reference = referenceRepository.findById((short) 1)
                .map(ReferenceSnapshotEntity::getPayload)
                .orElseThrow(() -> V1ApiException.conflict(
                        "REFERENCE_NOT_LOADED", "Reference data has not been uploaded"));

        validateEvent(date, request, payload, day, history, reference);

        eventRepository.save(new DispatchEventEntity(
                date, request.eventId(), request.eventType(), request.occurredAt(),
                request.orderId(), request.staffId(), request.targetStaffId(), payload));
        day.advanceModelNow(request.occurredAt());
        dayRepository.save(day);

        return new EventAcceptedResponse(
                "RECORDED_PENDING_REPLAN", request.eventId(), false, day.getModelNow());
    }

    @Transactional(readOnly = true)
    public EventHistoryResponse getHistory(LocalDate date) {
        DispatchDaySnapshotEntity day = dayRepository.findById(date)
                .orElseThrow(() -> V1ApiException.conflict(
                        "DAY_NOT_LOADED", "Day input has not been uploaded"));

        List<EventView> events = eventRepository
                .findAllByDayDateOrderByEventSequenceAsc(date)
                .stream()
                .map(event -> new EventView(
                        event.getEventSequence(), event.getEventId(), event.getEventType(),
                        event.getOccurredAt(), event.getOrderId(), event.getStaffId(),
                        event.getTargetStaffId(), event.getPayload(), event.getReceivedAt()))
                .toList();
        return new EventHistoryResponse(date, day.getModelNow(), events);
    }

    private JsonNode normalizePayload(JsonNode payload) {
        if (payload == null) return objectMapper.createObjectNode();
        if (!payload.isObject()) {
            throw V1ApiException.unprocessable(
                    "INVALID_EVENT_PAYLOAD", "payload must be a JSON object");
        }
        return payload;
    }

    private boolean hasSameContent(DispatchEventEntity existing,
                                   EventRequest request, JsonNode payload) {
        return existing.getEventType() == request.eventType()
                && existing.getOccurredAt().isEqual(request.occurredAt())
                && java.util.Objects.equals(existing.getOrderId(), request.orderId())
                && java.util.Objects.equals(existing.getStaffId(), request.staffId())
                && java.util.Objects.equals(existing.getTargetStaffId(), request.targetStaffId())
                && existing.getPayload().equals(payload);
    }

    private void validateEvent(LocalDate date, EventRequest request, JsonNode payload,
                               DispatchDaySnapshotEntity day,
                               List<DispatchEventEntity> history, JsonNode reference) {
        switch (request.eventType()) {
            case NEW_ORDER -> validateNewOrder(date, request, payload, day, history);
            case ORDER_CANCELLED -> {
                requireOrderId(request);
                requireKnownOrder(request.orderId(), day, history);
                rejectCancelledOrder(request.orderId(), history);
            }
            case VISIT_DELAYED -> {
                requireOrderId(request);
                requireKnownOrder(request.orderId(), day, history);
                rejectCancelledOrder(request.orderId(), history);
                JsonNode delay = payload.path("delay_min");
                if (!delay.isIntegralNumber() || delay.asInt() <= 0) {
                    throw V1ApiException.unprocessable(
                            "INVALID_DELAY", "VISIT_DELAYED payload.delay_min must be a positive integer");
                }
            }
            case STAFF_UNAVAILABLE -> {
                requireStaffId(request.staffId(), "staff_id");
                requireKnownStaff(request.staffId(), reference);
            }
            case ORDER_TRANSFERRED -> {
                requireOrderId(request);
                requireKnownOrder(request.orderId(), day, history);
                rejectCancelledOrder(request.orderId(), history);
                requireStaffId(request.targetStaffId(), "target_staff_id");
                requireKnownStaff(request.targetStaffId(), reference);
            }
        }
    }

    private void validateNewOrder(LocalDate date, EventRequest request, JsonNode payload,
                                  DispatchDaySnapshotEntity day,
                                  List<DispatchEventEntity> history) {
        requireOrderId(request);
        if (orderExists(request.orderId(), day, history)) {
            throw V1ApiException.unprocessable(
                    "ORDER_ALREADY_EXISTS", "NEW_ORDER refers to an order_id that is already present");
        }
        JsonNode orderNode = payload.path("order");
        if (!orderNode.isObject()) {
            throw V1ApiException.unprocessable(
                    "INVALID_NEW_ORDER", "NEW_ORDER payload must contain an order object");
        }

        final OrderDto order;
        try {
            order = objectMapper.treeToValue(orderNode, OrderDto.class);
        } catch (JsonProcessingException ex) {
            throw V1ApiException.unprocessable(
                    "INVALID_NEW_ORDER", "NEW_ORDER payload.order does not match the order schema");
        }

        Set<ConstraintViolation<OrderDto>> violations = validator.validate(order);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw V1ApiException.unprocessable("INVALID_NEW_ORDER", details);
        }
        if (!request.orderId().equals(order.orderId())) {
            throw V1ApiException.unprocessable(
                    "ORDER_ID_MISMATCH", "event order_id must match payload.order.order_id");
        }
        if (!order.registeredAt().toLocalDate().equals(date)
                || !order.slot().start().toLocalDate().equals(date)) {
            throw V1ApiException.unprocessable(
                    "NEW_ORDER_DATE_MISMATCH", "NEW_ORDER registered_at and slot.start must fall on the event day");
        }
        if (order.registeredAt().isAfter(request.occurredAt())) {
            throw V1ApiException.unprocessable(
                    "NEW_ORDER_TIME_MISMATCH", "NEW_ORDER registered_at cannot be later than occurred_at");
        }
    }

    private void requireOrderId(EventRequest request) {
        if (request.orderId() == null || request.orderId().isBlank()) {
            throw V1ApiException.unprocessable(
                    "ORDER_ID_REQUIRED", request.eventType() + " requires order_id");
        }
    }

    private void requireStaffId(String staffId, String field) {
        if (staffId == null || staffId.isBlank()) {
            throw V1ApiException.unprocessable(
                    "STAFF_ID_REQUIRED", field + " is required for this event type");
        }
    }

    private void requireKnownOrder(String orderId, DispatchDaySnapshotEntity day,
                                   List<DispatchEventEntity> history) {
        if (!orderExists(orderId, day, history)) {
            throw V1ApiException.unprocessable(
                    "UNKNOWN_ORDER", "Event refers to an order_id that is not present in the day or prior NEW_ORDER events");
        }
    }

    private boolean orderExists(String orderId, DispatchDaySnapshotEntity day,
                                List<DispatchEventEntity> history) {
        JsonNode orders = day.getPayload().path("orders");
        if (orders.isArray()) {
            for (JsonNode order : orders) {
                if (orderId.equals(order.path("order_id").asText())) return true;
            }
        }
        return history.stream().anyMatch(event ->
                event.getEventType() == EventType.NEW_ORDER
                        && orderId.equals(event.getOrderId()));
    }

    private void rejectCancelledOrder(String orderId, List<DispatchEventEntity> history) {
        boolean cancelled = history.stream().anyMatch(event ->
                event.getEventType() == EventType.ORDER_CANCELLED
                        && orderId.equals(event.getOrderId()));
        if (cancelled) {
            throw V1ApiException.unprocessable(
                    "ORDER_ALREADY_CANCELLED", "No further order events are accepted after cancellation");
        }
    }

    private void requireKnownStaff(String staffId, JsonNode reference) {
        JsonNode staff = reference.path("staff");
        if (staff.isArray()) {
            for (JsonNode member : staff) {
                if (staffId.equals(member.path("staff_id").asText())) return;
            }
        }
        throw V1ApiException.unprocessable(
                "UNKNOWN_STAFF", "Event refers to an unknown staff_id: " + staffId);
    }
}
