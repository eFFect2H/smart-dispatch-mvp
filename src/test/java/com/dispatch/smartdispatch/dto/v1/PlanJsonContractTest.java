package com.dispatch.smartdispatch.dto.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.*;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.EquipmentDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanJsonContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void planResponseUsesRequiredSnakeCaseContractFields() throws Exception {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-26T08:00:00+05:00");
        PlanResponse response = new PlanResponse(
                LocalDate.parse("2026-10-26"), 1, now,
                List.of(new RouteDto("ST-001", "SC-01", "ACTIVE", List.of(
                        new StopDto(1, "DEPOT_START", "SC-01", now, now, now,
                                null, 0L, 0L, "PLANNED", new EquipmentDto(2, 1, 0))
                ), new RouteTotals(0, 0L, 0L, 0, 0.0))),
                List.of(new UnassignedOrderDto("ORD-20261026-0001", "NO_QUALIFIED_STAFF",
                        "No qualified staff", "REASSIGN")),
                List.of(), List.of(), List.of(), 12L,
                new PlanSummary(1, 0, 1, 0, 0, 0L, 0.0)
        );

        JsonNode json = mapper.readTree(mapper.writeValueAsString(response));

        assertTrue(json.has("date"));
        assertTrue(json.has("plan_version"));
        assertTrue(json.has("as_of"));
        assertTrue(json.has("routes"));
        assertTrue(json.has("unassigned"));
        assertTrue(json.has("pgp_groups"));
        assertTrue(json.has("cancelled"));
        assertTrue(json.has("alerts"));
        assertEquals("ST-001", json.path("routes").get(0).path("staff_id").asText());
        assertEquals("DEPOT_START", json.path("routes").get(0).path("stops").get(0).path("type").asText());
        assertEquals("NO_QUALIFIED_STAFF", json.path("unassigned").get(0).path("reason_code").asText());
        assertEquals(2, json.path("routes").get(0).path("stops").get(0)
                .path("equipment_after").path("ONT").asInt());
        assertEquals(1, json.path("routes").get(0).path("stops").get(0)
                .path("equipment_after").path("ROUTER").asInt());
        assertEquals(0, json.path("routes").get(0).path("totals").path("visits").asInt());
        assertEquals(1, json.path("summary").path("orders_total").asInt());
    }
}
