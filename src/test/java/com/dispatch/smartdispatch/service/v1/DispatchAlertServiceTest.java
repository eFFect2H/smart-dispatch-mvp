package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.PlanDtos.AlertDto;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.TimeWindowDto;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DispatchAlertServiceTest {
    private static final LocalDate DATE = LocalDate.parse("2026-10-26");
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-10-26T08:00:00+05:00");
    private static final OffsetDateTime MODEL_NOW =
            OffsetDateTime.parse("2026-10-26T09:15:00+05:00");

    private ObjectMapper mapper;
    private DispatchAlertService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new DispatchAlertService(mapper);
    }

    @Test
    void keepsAlertIdAndCreationTimeWhileTheSameConditionRemainsActive() throws Exception {
        AlertDto previous = alert("ALR-20261026-0001", CREATED_AT, "ACTIVE", null,
                "UNASSIGNED", "Order is unassigned");
        AlertDto regenerated = alert("ALR-20261026-0001", MODEL_NOW, "ACTIVE", null,
                "UNASSIGNED", "Order is still unassigned");

        JsonNode previousPlan = planWith(previous);
        assertEquals(CREATED_AT.toString(),
                previousPlan.path("alerts").get(0).path("created_at").asText(),
                "The persisted alert snapshot must retain its original UTC offset");

        List<AlertDto> result = service.reconcile(previousPlan, List.of(regenerated),
                DATE, MODEL_NOW);

        assertEquals(1, result.size());
        assertEquals("ALR-20261026-0001", result.get(0).alertId());
        assertEquals(CREATED_AT, result.get(0).createdAt());
        assertEquals("ACTIVE", result.get(0).status());
        assertNull(result.get(0).resolvedAt());
        assertEquals("Order is still unassigned", result.get(0).message());
    }

    @Test
    void resolvesAnAlertWhenItsConditionDisappearsAndKeepsResolvedHistory() throws Exception {
        AlertDto active = alert("ALR-20261026-0001", CREATED_AT, "ACTIVE", null,
                "UNASSIGNED", "Order is unassigned");
        AlertDto historical = alert("ALR-20261026-0002", CREATED_AT.minusMinutes(10),
                "RESOLVED", CREATED_AT, "LATE_RISK", "Old lateness risk");

        List<AlertDto> result = service.reconcile(planWith(active, historical),
                List.of(), DATE, MODEL_NOW);

        assertEquals(2, result.size());
        AlertDto resolved = result.stream()
                .filter(value -> value.alertId().equals(active.alertId()))
                .findFirst().orElseThrow();
        assertEquals("RESOLVED", resolved.status());
        assertEquals(CREATED_AT, resolved.createdAt());
        assertEquals(MODEL_NOW, resolved.resolvedAt());

        AlertDto preservedHistory = result.stream()
                .filter(value -> value.alertId().equals(historical.alertId()))
                .findFirst().orElseThrow();
        assertEquals(historical, preservedHistory);
    }

    @Test
    void createsANewIdentityWhenAResolvedConditionAppearsAgain() throws Exception {
        AlertDto resolved = alert("ALR-20261026-0001", CREATED_AT, "RESOLVED", MODEL_NOW,
                "UNASSIGNED", "Order was previously unassigned");
        AlertDto reappeared = alert("ALR-20261026-0001", MODEL_NOW, "ACTIVE", null,
                "UNASSIGNED", "Order became unassigned again");

        List<AlertDto> result = service.reconcile(planWith(resolved), List.of(reappeared),
                DATE, MODEL_NOW.plusMinutes(5));

        assertEquals(2, result.size());
        AlertDto active = result.stream()
                .filter(value -> "ACTIVE".equals(value.status()))
                .findFirst().orElseThrow();
        AlertDto old = result.stream()
                .filter(value -> "RESOLVED".equals(value.status()))
                .findFirst().orElseThrow();

        assertEquals("ALR-20261026-0002", active.alertId());
        assertEquals(MODEL_NOW, active.createdAt());
        assertEquals(resolved.alertId(), old.alertId());
        assertEquals(MODEL_NOW, old.resolvedAt());
    }

    private JsonNode planWith(AlertDto... alerts) {
        // Match DispatchPlanService's persisted-plan serializer: OffsetDateTime
        // values are stored with their original offset, not normalized to UTC.
        SimpleModule snapshotTimeModule = new SimpleModule("test-plan-snapshot-offset-date-time");
        snapshotTimeModule.addSerializer(OffsetDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(OffsetDateTime value, JsonGenerator generator,
                                  SerializerProvider serializers) throws IOException {
                generator.writeString(value.toString());
            }
        });
        ObjectMapper snapshotMapper = mapper.copy().registerModule(snapshotTimeModule);
        return snapshotMapper.valueToTree(Map.of("alerts", List.of(alerts)));
    }

    private AlertDto alert(String id, OffsetDateTime createdAt, String status,
                           OffsetDateTime resolvedAt, String type, String message) {
        return new AlertDto(
                id, createdAt, type, status, "RESCHEDULE", message,
                resolvedAt, "ORD-20261026-0001", null, null,
                null, null, null, (TimeWindowDto) null, false
        );
    }
}
