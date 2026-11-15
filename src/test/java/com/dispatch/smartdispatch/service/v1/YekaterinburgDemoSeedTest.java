package com.dispatch.smartdispatch.service.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class YekaterinburgDemoSeedTest {
    private static final String MIGRATION =
            "db/migration/V6__seed_yekaterinburg_demo_dataset.sql";
    private static final OffsetDateTime MODEL_NOW =
            OffsetDateTime.parse("2026-11-14T08:45:00+05:00");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void databaseSeedContainsConsistentYekaterinburgDemoData() throws Exception {
        String sql = readMigration();
        JsonNode reference = readDollarQuotedJson(sql, "$REF$");
        JsonNode orders = readDollarQuotedJson(sql, "$ORDERS$");

        assertEquals("Екатеринбург", sql.contains("'Екатеринбург'") ? "Екатеринбург" : "");
        assertEquals(3, reference.path("service_centers").size());
        assertEquals(8, reference.path("staff").size());
        assertEquals(22, reference.path("access_nodes").size());
        assertEquals(8, reference.path("work_types").size());
        assertEquals(34, orders.size());
        assertEquals(56.8382, reference.path("service_centers").get(0).path("lat").asDouble(), 0.0001);
        assertEquals(60.6008, reference.path("service_centers").get(0).path("lon").asDouble(), 0.0001);

        Map<String, JsonNode> workTypes = new HashMap<>();
        reference.path("work_types").forEach(workType ->
                workTypes.put(workType.path("code").asText(), workType));

        Map<String, JsonNode> buildings = new HashMap<>();
        Map<String, JsonNode> nodeByBuilding = new HashMap<>();
        Set<String> accessNodeIds = new HashSet<>();
        reference.path("access_nodes").forEach(node -> {
            accessNodeIds.add(node.path("access_node_id").asText());
            node.path("buildings").forEach(building -> {
                assertNull(buildings.put(building.path("building_id").asText(), building),
                        "building IDs must be unique");
                nodeByBuilding.put(building.path("building_id").asText(), node);
            });
        });
        assertEquals(22, buildings.size());

        Set<String> orderIds = new HashSet<>();
        Map<String, List<OffsetDateTime>> pgpRegistrations = new HashMap<>();
        for (JsonNode order : orders) {
            String orderId = order.path("order_id").asText();
            assertTrue(orderId.matches("ORD-20261114-[0-9]{4}"), orderId);
            assertTrue(orderIds.add(orderId), "order IDs must be unique");
            assertTrue(accessNodeIds.contains(order.path("access_node_id").asText()), orderId);

            JsonNode workType = workTypes.get(order.path("work_type_code").asText());
            assertNotNull(workType, "unknown work type for " + orderId);
            JsonNode building = buildings.get(order.path("building_id").asText());
            assertNotNull(building, "unknown building for " + orderId);
            JsonNode accessNode = nodeByBuilding.get(order.path("building_id").asText());
            assertEquals(order.path("access_node_id").asText(), accessNode.path("access_node_id").asText());
            assertEquals(order.path("technology").asText(), accessNode.path("technology").asText());
            assertEquals(order.path("building_type").asText(), building.path("building_type").asText());
            assertEquals(order.path("lat").asDouble(), building.path("lat").asDouble(), 0.000001);
            assertEquals(order.path("lon").asDouble(), building.path("lon").asDouble(), 0.000001);

            OffsetDateTime registeredAt = OffsetDateTime.parse(order.path("registered_at").asText());
            OffsetDateTime slotStart = OffsetDateTime.parse(order.path("slot").path("start").asText());
            OffsetDateTime slotEnd = OffsetDateTime.parse(order.path("slot").path("end").asText());
            OffsetDateTime latestStart = OffsetDateTime.parse(order.path("latest_start").asText());
            OffsetDateTime deadline = OffsetDateTime.parse(order.path("deadline").asText());
            OffsetDateTime expectedLatestStart = slotStart.plusMinutes(
                    workType.path("max_start_delay_min").asLong());
            if (expectedLatestStart.isAfter(slotEnd)) expectedLatestStart = slotEnd;

            assertEquals(LocalDate.parse("2026-11-14"), slotStart.toLocalDate());
            assertFalse(registeredAt.isAfter(MODEL_NOW), orderId + " must be registered before model_now");
            assertTrue(slotStart.isBefore(slotEnd), orderId + " must have a non-empty slot");
            assertEquals(expectedLatestStart, latestStart, orderId + " latest_start must match work type");
            assertTrue(deadline.isAfter(latestStart.plusMinutes(workType.path("duration_min").asLong())),
                    orderId + " deadline must allow a latest-start visit to finish");
            assertTrue(order.path("lat").asDouble() > 56.7 && order.path("lat").asDouble() < 57.0);
            assertTrue(order.path("lon").asDouble() > 60.4 && order.path("lon").asDouble() < 60.8);

            if (workType.path("pgp_relevant").asBoolean()) {
                pgpRegistrations.computeIfAbsent(order.path("access_node_id").asText(), ignored ->
                        new ArrayList<>()).add(registeredAt);
            }

            boolean qualifiedStaffExists = false;
            for (JsonNode staff : reference.path("staff")) {
                boolean profileMatch = false;
                for (JsonNode staffProfile : staff.path("profiles")) {
                    for (JsonNode requiredProfile : workType.path("profiles")) {
                        profileMatch |= staffProfile.asText().equals(requiredProfile.asText());
                    }
                }
                boolean technologyMatch = false;
                for (JsonNode technology : staff.path("technologies")) {
                    technologyMatch |= technology.asText().equals(order.path("technology").asText());
                }
                boolean heightMatch = !order.path("requires_height").asBoolean()
                        || staff.path("height_permit").asBoolean();
                if (profileMatch && technologyMatch && heightMatch) {
                    qualifiedStaffExists = true;
                    break;
                }
            }
            assertTrue(qualifiedStaffExists, orderId + " must have at least one qualified staff member");
        }

        assertEquals(34, orderIds.size());
        boolean hasPgpGroup = pgpRegistrations.values().stream().anyMatch(times -> {
            if (times.size() < 3) return false;
            OffsetDateTime earliest = times.stream().min(OffsetDateTime::compareTo).orElseThrow();
            OffsetDateTime latest = times.stream().max(OffsetDateTime::compareTo).orElseThrow();
            return Duration.between(earliest, latest).compareTo(Duration.ofMinutes(60)) <= 0;
        });
        assertTrue(hasPgpGroup, "demo data must contain a PGP candidate group");
    }

    private String readMigration() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(stream, "Flyway demo migration must be available on the test classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonNode readDollarQuotedJson(String sql, String marker) throws IOException {
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "missing SQL marker " + marker);
        start += marker.length();
        int end = sql.indexOf(marker, start);
        assertTrue(end > start, "unterminated SQL marker " + marker);
        return mapper.readTree(sql.substring(start, end));
    }
}
