package com.dispatch.smartdispatch.service.v1;

import com.dispatch.smartdispatch.dto.v1.DemoDataDtos.DemoDayResponse;
import com.dispatch.smartdispatch.dto.v1.DemoDataDtos.DemoInfoResponse;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.DayInputRequest;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.OrderDto;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.TravelMatrixRequest;
import com.dispatch.smartdispatch.entity.v1.DemoDatasetEntity;
import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import com.dispatch.smartdispatch.entity.v1.TravelMatrixSnapshotEntity;
import com.dispatch.smartdispatch.exception.v1.V1ApiException;
import com.dispatch.smartdispatch.repository.v1.DemoDatasetRepository;
import com.dispatch.smartdispatch.repository.v1.DispatchDaySnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.ReferenceSnapshotRepository;
import com.dispatch.smartdispatch.repository.v1.TravelMatrixSnapshotRepository;
import com.dispatch.smartdispatch.routing.Coordinate;
import com.dispatch.smartdispatch.routing.OsrmMatrixClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class DemoDataService {
    private static final short DATASET_ID = 1;
    private static final short SNAPSHOT_ID = 1;
    private static final LocalTime INITIAL_MODEL_TIME = LocalTime.of(8, 45);

    private final DemoDatasetRepository datasetRepository;
    private final ReferenceSnapshotRepository referenceRepository;
    private final TravelMatrixSnapshotRepository matrixRepository;
    private final DispatchDaySnapshotRepository dayRepository;
    private final TravelMatrixService matrixService;
    private final DayInputService dayInputService;
    private final OsrmMatrixClient osrmMatrixClient;
    private final ObjectMapper objectMapper;

    public DemoDataService(DemoDatasetRepository datasetRepository,
                           ReferenceSnapshotRepository referenceRepository,
                           TravelMatrixSnapshotRepository matrixRepository,
                           DispatchDaySnapshotRepository dayRepository,
                           TravelMatrixService matrixService,
                           DayInputService dayInputService,
                           OsrmMatrixClient osrmMatrixClient,
                           ObjectMapper objectMapper) {
        this.datasetRepository = datasetRepository;
        this.referenceRepository = referenceRepository;
        this.matrixRepository = matrixRepository;
        this.dayRepository = dayRepository;
        this.matrixService = matrixService;
        this.dayInputService = dayInputService;
        this.osrmMatrixClient = osrmMatrixClient;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public DemoInfoResponse getInfo() {
        DemoDatasetEntity dataset = requireDataset();
        ReferenceSnapshotEntity reference = requireReference();
        ZoneId zone = ZoneId.of(dataset.getTimezoneId());
        LocalDate today = LocalDate.now(zone);
        OffsetDateTime initialModelNow = today.atTime(INITIAL_MODEL_TIME).atZone(zone).toOffsetDateTime();

        return new DemoInfoResponse(
                dataset.getCityName(),
                dataset.getTimezoneId(),
                dataset.getCenterLat(),
                dataset.getCenterLon(),
                today,
                initialModelNow,
                dataset.getDatasetVersion(),
                reference.getServiceCenterCount(),
                reference.getStaffCount(),
                reference.getAccessNodeCount(),
                reference.getBuildingCount(),
                dataset.getOrderCount()
        );
    }

    /**
     * Ensures the city reference and OSRM matrix are present in PostgreSQL,
     * then creates the requested day's input from the database-stored demo template.
     * Repeated calls are idempotent and do not overwrite an existing day or its events.
     */
    @Transactional
    public DemoDayResponse initializeDay(LocalDate date) {
        DemoDatasetEntity dataset = requireDataset();
        ReferenceSnapshotEntity reference = requireReference();
        String matrixId = ensureTravelMatrix(dataset, reference.getPayload());

        DispatchDaySnapshotEntity day = dayRepository.findById(date).orElse(null);
        if (day == null) {
            DayInputRequest request = createDayInput(dataset, date);
            dayInputService.replace(date, request);
            day = dayRepository.findById(date).orElseThrow(() ->
                    new IllegalStateException("The initialized demo day was not persisted"));
        }

        final DayInputRequest storedInput;
        try {
            storedInput = objectMapper.treeToValue(day.getPayload(), DayInputRequest.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not read the database-stored demo day " + date, ex);
        }

        return new DemoDayResponse(
                dataset.getCityName(),
                date,
                day.getModelNow(),
                dataset.getDatasetVersion(),
                matrixId,
                reference.getStaffCount(),
                reference.getBuildingCount(),
                day.getOrderCount(),
                storedInput.orders()
        );
    }

    private String ensureTravelMatrix(DemoDatasetEntity dataset, JsonNode reference) {
        String matrixId = "yekaterinburg-osrm-" + dataset.getDatasetVersion();
        List<String> points = new ArrayList<>();
        List<Coordinate> coordinates = new ArrayList<>();

        reference.path("service_centers").forEach(center -> {
            points.add(center.path("service_center_id").asText());
            coordinates.add(new Coordinate(
                    center.path("lon").asDouble(),
                    center.path("lat").asDouble()
            ));
        });
        reference.path("access_nodes").forEach(node ->
                node.path("buildings").forEach(building -> {
                    points.add(building.path("building_id").asText());
                    coordinates.add(new Coordinate(
                            building.path("lon").asDouble(),
                            building.path("lat").asDouble()
                    ));
                }));

        TravelMatrixSnapshotEntity stored = matrixRepository.findById(SNAPSHOT_ID).orElse(null);
        if (stored != null && matrixId.equals(stored.getMatrixId())
                && points.equals(readPoints(stored.getPayload()))) {
            return matrixId;
        }
        if (points.size() < 2) {
            throw V1ApiException.conflict("DEMO_REFERENCE_INVALID",
                    "The Yekaterinburg demo dataset must contain a service center and buildings");
        }

        OsrmMatrixClient.RoadMatrix roadMatrix = osrmMatrixClient.getRoadMatrix(coordinates);
        matrixService.replace(new TravelMatrixRequest(
                matrixId,
                List.copyOf(points),
                roadMatrix.durationsSec(),
                roadMatrix.distancesM(),
                List.of()
        ));
        return matrixId;
    }

    private List<String> readPoints(JsonNode payload) {
        List<String> points = new ArrayList<>();
        payload.path("points").forEach(point -> points.add(point.asText()));
        return points;
    }

    private DayInputRequest createDayInput(DemoDatasetEntity dataset, LocalDate date) {
        ZoneId zone = ZoneId.of(dataset.getTimezoneId());
        OffsetDateTime modelNow = date.atTime(INITIAL_MODEL_TIME).atZone(zone).toOffsetDateTime();
        JsonNode template = dataset.getOrdersPayload();
        JsonNode sourceOrders = template.isArray() ? template : template.path("orders");
        if (!sourceOrders.isArray()) {
            throw new IllegalStateException("The database demo order template must be a JSON array");
        }

        ArrayNode orders = objectMapper.createArrayNode();
        sourceOrders.forEach(source -> {
            ObjectNode order = source.deepCopy();
            order.put("order_id", rebaseOrderId(order.path("order_id").asText(), date));
            if (!order.path("previous_order_id").isMissingNode()
                    && !order.path("previous_order_id").isNull()) {
                order.put("previous_order_id", rebaseOrderId(order.path("previous_order_id").asText(), date));
            }
            order.put("registered_at", rebaseTimestamp(order.path("registered_at"), date));
            ObjectNode slot = (ObjectNode) order.path("slot");
            slot.put("start", rebaseTimestamp(slot.path("start"), date));
            slot.put("end", rebaseTimestamp(slot.path("end"), date));
            order.put("latest_start", rebaseTimestamp(order.path("latest_start"), date));
            order.put("deadline", rebaseTimestamp(order.path("deadline"), date));
            orders.add(order);
        });

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("date", date.toString());
        payload.put("now", modelNow.toString());
        payload.set("orders", orders);
        payload.putArray("absent_staff_ids");

        try {
            return objectMapper.treeToValue(payload, DayInputRequest.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not construct the Yekaterinburg demo day " + date, ex);
        }
    }

    private String rebaseOrderId(String orderId, LocalDate date) {
        int separator = orderId.lastIndexOf('-');
        if (!orderId.startsWith("ORD-") || separator < 4 || separator == orderId.length() - 1) {
            throw new IllegalStateException("Invalid order_id in database demo template: " + orderId);
        }
        return "ORD-" + date.toString().replace("-", "") + orderId.substring(separator);
    }

    private String rebaseTimestamp(JsonNode value, LocalDate date) {
        try {
            OffsetDateTime original = OffsetDateTime.parse(value.asText());
            return OffsetDateTime.of(date, original.toLocalTime(), original.getOffset()).toString();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid timestamp in the database demo order template: " + value, ex);
        }
    }

    private DemoDatasetEntity requireDataset() {
        return datasetRepository.findById(DATASET_ID).orElseThrow(() ->
                V1ApiException.conflict("DEMO_DATA_NOT_SEEDED",
                        "The Yekaterinburg demo dataset is not installed. Apply the latest Flyway migrations."));
    }

    private ReferenceSnapshotEntity requireReference() {
        return referenceRepository.findById(SNAPSHOT_ID).orElseThrow(() ->
                V1ApiException.conflict("REFERENCE_NOT_LOADED",
                        "The database has no active reference snapshot for the demo dataset."));
    }
}
