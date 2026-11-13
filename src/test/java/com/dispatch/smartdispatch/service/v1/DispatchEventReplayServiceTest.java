package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.EventType;
import com.dispatch.smartdispatch.entity.v1.DispatchEventEntity;
import com.dispatch.smartdispatch.repository.v1.DispatchEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchEventReplayServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-26");
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-26T08:00:00+05:00");

    private DispatchEventRepository eventRepository;
    private ObjectMapper mapper;
    private DispatchEventReplayService replayService;
    private JsonNode baseDay;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        eventRepository = mock(DispatchEventRepository.class);
        replayService = new DispatchEventReplayService(eventRepository, mapper);
        baseDay = mapper.readTree("""
                {
                  "absent_staff_ids": [],
                  "orders": [{
                    "order_id":"ORD-20261026-0001",
                    "work_type_code":"REPAIR",
                    "technology":"PON",
                    "building_id":"B-00001",
                    "registered_at":"2026-10-26T08:00:00+05:00",
                    "slot":{"start":"2026-10-26T09:00:00+05:00","end":"2026-10-26T11:00:00+05:00"},
                    "latest_start":"2026-10-26T09:30:00+05:00",
                    "deadline":"2026-10-26T10:00:00+05:00",
                    "required_equipment":{"ONT":1,"ROUTER":0,"STB":0}
                  }]
                }
                """);
        when(eventRepository.findAllByDayDateOrderByEventSequenceAsc(DATE)).thenReturn(List.of());
    }

    @Test
    void replaysNewOrderDelaysTransfersAndStaffUnavailabilityInSequence() throws Exception {
        JsonNode newOrder = mapper.readTree("""
                {
                  "order": {
                    "order_id":"ORD-20261026-0002",
                    "work_type_code":"REPAIR",
                    "technology":"PON",
                    "building_id":"B-00002",
                    "registered_at":"2026-10-26T08:05:00+05:00",
                    "slot":{"start":"2026-10-26T14:00:00+05:00","end":"2026-10-26T15:00:00+05:00"},
                    "latest_start":"2026-10-26T14:30:00+05:00",
                    "deadline":"2026-10-26T15:00:00+05:00",
                    "required_equipment":{"ONT":1,"ROUTER":0,"STB":0}
                  }
                }
                """);
        List<DispatchEventEntity> events = List.of(
                event("EVT-NEW", EventType.NEW_ORDER, NOW.plusMinutes(5),
                        "ORD-20261026-0002", null, null, newOrder),
                event("EVT-DELAY-1", EventType.VISIT_DELAYED, NOW.plusMinutes(10),
                        "ORD-20261026-0002", null, null, mapper.createObjectNode().put("delay_min", 20)),
                event("EVT-DELAY-2", EventType.VISIT_DELAYED, NOW.plusMinutes(15),
                        "ORD-20261026-0002", null, null, mapper.createObjectNode().put("delay_min", 10)),
                event("EVT-TRANSFER", EventType.ORDER_TRANSFERRED, NOW.plusMinutes(20),
                        "ORD-20261026-0002", null, "ST-002", mapper.createObjectNode()),
                event("EVT-ABSENT", EventType.STAFF_UNAVAILABLE, NOW.plusMinutes(25),
                        null, "ST-001", null, mapper.createObjectNode())
        );
        when(eventRepository.findAllByDayDateOrderByEventSequenceAsc(DATE)).thenReturn(events);

        DispatchEventReplayService.ReplayResult replay =
                replayService.replay(DATE, baseDay, Set.of());

        JsonNode appended = replay.effectiveDayPayload().path("orders").get(1);
        assertEquals(2, replay.effectiveDayPayload().path("orders").size());
        assertEquals("2026-10-26T14:30+05:00", appended.path("slot").path("start").asText());
        assertEquals("2026-10-26T15:30+05:00", appended.path("slot").path("end").asText());
        assertEquals("2026-10-26T15:00+05:00", appended.path("latest_start").asText());
        assertEquals("2026-10-26T15:30+05:00", appended.path("deadline").asText());
        assertEquals("ST-002", replay.transferTargets().get("ORD-20261026-0002"));
        assertTrue(replay.effectiveDayPayload().path("absent_staff_ids")
                .toString().contains("ST-001"));
        assertEquals(1, baseDay.path("orders").size(),
                "Projection must not mutate the original day payload");
        assertEquals("2026-10-26T09:00:00+05:00",
                baseDay.path("orders").get(0).path("slot").path("start").asText(),
                "The original day snapshot must remain unchanged");
        assertEquals("2026-10-26T14:00:00+05:00",
                newOrder.path("order").path("slot").path("start").asText(),
                "Replay must not mutate the NEW_ORDER event payload");
    }

    @Test
    void cancellationMovesAnUnfrozenOrderToCancelledAndRemovesTransferTarget() {
        OffsetDateTime cancelledAt = NOW.plusMinutes(5);
        List<DispatchEventEntity> events = List.of(
                event("EVT-TRANSFER", EventType.ORDER_TRANSFERRED, NOW.plusMinutes(1),
                        "ORD-20261026-0001", null, "ST-002", mapper.createObjectNode()),
                event("EVT-CANCEL", EventType.ORDER_CANCELLED, cancelledAt,
                        "ORD-20261026-0001", null, null, mapper.createObjectNode())
        );
        when(eventRepository.findAllByDayDateOrderByEventSequenceAsc(DATE)).thenReturn(events);

        DispatchEventReplayService.ReplayResult replay =
                replayService.replay(DATE, baseDay, Set.of());

        assertEquals(Set.of("ORD-20261026-0001"), replay.cancelledOrderIds());
        assertEquals(1, replay.cancelledOrders().size());
        assertEquals(cancelledAt, replay.cancelledOrders().get(0).cancelledAt());
        assertTrue(replay.transferTargets().isEmpty());
    }

    @Test
    void frozenOrderIgnoresCancellationDelayAndTransferEvents() {
        List<DispatchEventEntity> events = List.of(
                event("EVT-TRANSFER", EventType.ORDER_TRANSFERRED, NOW.plusMinutes(1),
                        "ORD-20261026-0001", null, "ST-002", mapper.createObjectNode()),
                event("EVT-DELAY", EventType.VISIT_DELAYED, NOW.plusMinutes(2),
                        "ORD-20261026-0001", null, null, mapper.createObjectNode().put("delay_min", 45)),
                event("EVT-CANCEL", EventType.ORDER_CANCELLED, NOW.plusMinutes(3),
                        "ORD-20261026-0001", null, null, mapper.createObjectNode())
        );
        when(eventRepository.findAllByDayDateOrderByEventSequenceAsc(DATE)).thenReturn(events);

        DispatchEventReplayService.ReplayResult replay =
                replayService.replay(DATE, baseDay, Set.of("ORD-20261026-0001"));

        JsonNode order = replay.effectiveDayPayload().path("orders").get(0);
        assertEquals("2026-10-26T09:00:00+05:00", order.path("slot").path("start").asText());
        assertTrue(replay.cancelledOrders().isEmpty());
        assertTrue(replay.transferTargets().isEmpty());
    }

    private DispatchEventEntity event(String eventId, EventType type, OffsetDateTime occurredAt,
                                      String orderId, String staffId, String targetStaffId,
                                      JsonNode payload) {
        return new DispatchEventEntity(
                DATE, eventId, type, occurredAt, orderId, staffId, targetStaffId, payload);
    }
}
