package com.dispatch.smartdispatch.dto.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.EquipmentDto;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.OrderDto;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class V1JsonContractTest {
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    }

    @Test
    void equipmentUsesContractUppercaseNames() throws Exception {
        String json = objectMapper.writeValueAsString(new EquipmentDto(2, 3, 1));
        JsonNode actual = objectMapper.readTree(json);
        JsonNode expected = objectMapper.readTree("""
                {"ONT":2,"ROUTER":3,"STB":1}
                """);
        assertEquals(expected, actual);
    }

    @Test
    void strictTimeWindowRejectsUnknownProperties() {
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class,
                () -> objectMapper.readValue("""
                        {"start":"2026-10-26T09:00:00+05:00",
                         "end":"2026-10-26T11:00:00+05:00",
                         "extra":"not allowed"}
                        """, V1Dtos.TimeWindowDto.class));
    }

    @Test
    void orderUsesSnakeCaseAndPreservesOffsetAndIgnoresTrainingFact() throws Exception {
        String json = """
                {
                  "order_id": "ORD-20261026-0001",
                  "work_type_code": "REPAIR",
                  "technology": "PON",
                  "building_id": "B-00412",
                  "address": "г. Екатеринбург, ул. Учителей, 18",
                  "lat": 56.8712,
                  "lon": 60.6531,
                  "building_type": "MKD",
                  "requires_height": false,
                  "access_node_id": "AN-0031",
                  "registered_at": "2026-10-26T08:00:00+05:00",
                  "slot": {
                    "start": "2026-10-26T09:00:00+05:00",
                    "end": "2026-10-26T11:00:00+05:00"
                  },
                  "latest_start": "2026-10-26T09:10:00+05:00",
                  "deadline": "2026-10-26T10:10:00+05:00",
                  "required_equipment": {"ONT": 0, "ROUTER": 1, "STB": 0},
                  "is_repeat": false,
                  "fact": {"started_at": "2026-10-26T09:05:00+05:00"}
                }
                """;

        OrderDto order = objectMapper.readValue(json, OrderDto.class);

        assertEquals("ORD-20261026-0001", order.orderId());
        assertEquals(1, order.requiredEquipment().router());
        assertEquals(9, order.slot().start().getHour());
        assertEquals(0, order.slot().start().getMinute());
        assertEquals(5 * 60 * 60, order.slot().start().getOffset().getTotalSeconds());
    }
}
