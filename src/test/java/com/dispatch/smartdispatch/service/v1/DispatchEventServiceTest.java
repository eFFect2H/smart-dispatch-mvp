package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.EventRequest;
import com.dispatch.smartdispatch.dto.v1.EventDtos.EventType;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.DispatchEventEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.DispatchEventRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DispatchEventServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-26");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T08:00:00+05:00");

    private DispatchDaySnapshotRepository dayRepository;
    private DispatchEventRepository eventRepository;
    private ReferenceSnapshotRepository referenceRepository;
    private DispatchDaySnapshotEntity day;
    private ObjectMapper mapper;
    private Validator validator;
    private DispatchEventService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        validator = Validation.buildDefaultValidatorFactory().getValidator();
        dayRepository = mock(DispatchDaySnapshotRepository.class);
        eventRepository = mock(DispatchEventRepository.class);
        referenceRepository = mock(ReferenceSnapshotRepository.class);

        day = new DispatchDaySnapshotEntity(
                DATE, NOW, mapper.readTree("""
                    {"orders":[{"order_id":"ORD-20261026-0001"}]}
                    """), 1);
        ReferenceSnapshotEntity reference = new ReferenceSnapshotEntity(
                mapper.readTree("""
                    {"staff":[{"staff_id":"ST-001"}]}
                    """), 0, 1, 0, 0, 0);

        when(dayRepository.findByDateForUpdate(DATE)).thenReturn(Optional.of(day));
        when(dayRepository.findById(DATE)).thenReturn(Optional.of(day));
        when(referenceRepository.findById((short) 1)).thenReturn(Optional.of(reference));
        when(eventRepository.findAllByDayDateOrderByEventSequenceAsc(DATE)).thenReturn(List.of());
        when(eventRepository.findByDayDateAndEventId(any(), any())).thenReturn(Optional.empty());
        when(eventRepository.save(any(DispatchEventEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service = new DispatchEventService(
                dayRepository, eventRepository, referenceRepository, mapper, validator);
    }

    @Test
    void storesEventAndAdvancesModelTime() {
        OffsetDateTime occurredAt = NOW.plusMinutes(15);
        EventRequest request = new EventRequest(
                "EVT-001", EventType.ORDER_CANCELLED, occurredAt,
                "ORD-20261026-0001", null, null, mapper.createObjectNode());

        var response = service.append(DATE, request);

        assertEquals("RECORDED_PENDING_REPLAN", response.status());
        assertFalse(response.duplicate());
        assertEquals(occurredAt, response.modelNow());
        assertEquals(occurredAt, day.getModelNow());
        verify(eventRepository).save(any(DispatchEventEntity.class));
        verify(dayRepository).save(day);
    }

    @Test
    void returnsExistingResultForAnIdempotentRetry() throws Exception {
        OffsetDateTime occurredAt = NOW.plusMinutes(5);
        DispatchEventEntity existing = new DispatchEventEntity(
                DATE, "EVT-002", EventType.ORDER_CANCELLED, occurredAt,
                "ORD-20261026-0001", null, null, mapper.createObjectNode());
        day.advanceModelNow(NOW.plusMinutes(20));
        when(eventRepository.findByDayDateAndEventId(DATE, "EVT-002"))
                .thenReturn(Optional.of(existing));

        EventRequest retry = new EventRequest(
                "EVT-002", EventType.ORDER_CANCELLED, occurredAt,
                "ORD-20261026-0001", null, null, mapper.createObjectNode());

        var response = service.append(DATE, retry);

        assertTrue(response.duplicate());
        assertEquals("ALREADY_RECORDED", response.status());
        assertEquals(NOW.plusMinutes(20), response.modelNow());
        verify(eventRepository, never()).save(any(DispatchEventEntity.class));
    }

    @Test
    void rejectsReusingEventIdForDifferentContent() {
        DispatchEventEntity existing = new DispatchEventEntity(
                DATE, "EVT-003", EventType.ORDER_CANCELLED, NOW.plusMinutes(5),
                "ORD-20261026-0001", null, null, mapper.createObjectNode());
        when(eventRepository.findByDayDateAndEventId(DATE, "EVT-003"))
                .thenReturn(Optional.of(existing));

        EventRequest conflicting = new EventRequest(
                "EVT-003", EventType.VISIT_DELAYED, NOW.plusMinutes(5),
                "ORD-20261026-0001", null, null,
                mapper.createObjectNode().put("delay_min", 10));

        V1ApiException exception = assertThrows(
                V1ApiException.class, () -> service.append(DATE, conflicting));

        assertEquals("EVENT_ID_CONFLICT", exception.getCode());
    }

    @Test
    void rejectsEventThatMovesModelTimeBackwards() {
        day.advanceModelNow(NOW.plusMinutes(10));
        EventRequest request = new EventRequest(
                "EVT-004", EventType.ORDER_CANCELLED, NOW.plusMinutes(5),
                "ORD-20261026-0001", null, null, mapper.createObjectNode());

        V1ApiException exception = assertThrows(
                V1ApiException.class, () -> service.append(DATE, request));

        assertEquals("EVENT_TIME_REGRESSION", exception.getCode());
        verify(eventRepository, never()).save(any(DispatchEventEntity.class));
    }

    @Test
    void rejectsDelayWithoutPositiveDelayMinutes() {
        EventRequest request = new EventRequest(
                "EVT-005", EventType.VISIT_DELAYED, NOW.plusMinutes(5),
                "ORD-20261026-0001", null, null,
                mapper.createObjectNode().put("delay_min", 0));

        V1ApiException exception = assertThrows(
                V1ApiException.class, () -> service.append(DATE, request));

        assertEquals("INVALID_DELAY", exception.getCode());
    }

    @Test
    void rejectsUnknownStaffForStaffUnavailableEvent() {
        EventRequest request = new EventRequest(
                "EVT-006", EventType.STAFF_UNAVAILABLE, NOW.plusMinutes(5),
                null, "ST-999", null, mapper.createObjectNode());

        V1ApiException exception = assertThrows(
                V1ApiException.class, () -> service.append(DATE, request));

        assertEquals("UNKNOWN_STAFF", exception.getCode());
    }

    @Test
    void acceptsNewOrderWithValidatedOrderPayload() throws Exception {
        OffsetDateTime occurredAt = NOW.plusMinutes(10);
        var payload = mapper.readTree("""
                {
                  "order": {
                    "order_id":"ORD-20261026-0002",
                    "work_type_code":"REPAIR",
                    "technology":"PON",
                    "building_id":"B-00002",
                    "address":"Berlin, Teststrasse 2",
                    "lat":52.5,
                    "lon":13.4,
                    "building_type":"MKD",
                    "requires_height":false,
                    "access_node_id":"AN-0001",
                    "registered_at":"2026-10-26T08:10:00+05:00",
                    "slot":{"start":"2026-10-26T10:00:00+05:00","end":"2026-10-26T12:00:00+05:00"},
                    "latest_start":"2026-10-26T10:30:00+05:00",
                    "deadline":"2026-10-26T11:00:00+05:00",
                    "required_equipment":{"ONT":0,"ROUTER":0,"STB":0},
                    "is_repeat":false,
                    "previous_order_id":null
                  }
                }
                """);
        EventRequest request = new EventRequest(
                "EVT-007", EventType.NEW_ORDER, occurredAt,
                "ORD-20261026-0002", null, null, payload);

        var response = service.append(DATE, request);

        assertFalse(response.duplicate());
        assertEquals(occurredAt, response.modelNow());
        verify(eventRepository).save(any(DispatchEventEntity.class));
    }
}
