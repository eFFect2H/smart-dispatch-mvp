package com.dispatch.smartdispatch.routing;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class OsrmMatrixClient
{
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String profile;

    private static final Logger log =
            LoggerFactory.getLogger(OsrmMatrixClient.class);

    public OsrmMatrixClient(
            RestTemplateBuilder builder,
            @Value("${routing.osrm.base-url}") String baseUrl,
            @Value("${routing.osrm.profile:driving}") String profile,
            @Value("${routing.osrm.connect-timeout-seconds:5}") long connectTimeoutSeconds,
            @Value("${routing.osrm.read-timeout-seconds:30}") long readTimeoutSeconds) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
                .build();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.profile = profile;
    }

    /** Returns a square matrix of integer travel minutes, using road-network durations from OSRM. */
    public int[][] getTravelTimeMinutes(List<Coordinate> coordinates)
    {

        if (coordinates.isEmpty())
            return new int[0][0];

        if (coordinates.size() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MVP ограничивает размер матрицы 100 точками. Разбейте расчёт на зоны.");
        }

        String joinedCoordinates = coordinates.stream()
                .map(point -> point.longitude() + "," + point.latitude())
                .collect(Collectors.joining(";"));
        String url = baseUrl + "/table/v1/" + profile + "/" + joinedCoordinates + "?annotations=duration";

        final JsonNode json;
        try
        {
            json = restTemplate.getForObject(url, JsonNode.class);
        } catch (RestClientException ex)
        {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Не удалось обратиться к OSRM. Проверьте интернет и routing.osrm.base-url.", ex);
        }

        if (json == null || !"Ok".equals(json.path("code").asText())) {
            String error = json == null ? "пустой ответ" : json.path("message").asText("неизвестная ошибка");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OSRM вернул ошибку: " + error);
        }

        JsonNode durations = json.path("durations");
        if (!durations.isArray() || durations.size() != coordinates.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OSRM вернул матрицу неверного размера.");
        }

        int size = coordinates.size();
        int[][] matrix = new int[size][size];
        for (int from = 0; from < size; from++)
        {
            JsonNode row = durations.path(from);
            if (!row.isArray() || row.size() != size)
            {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OSRM вернул некорректную строку матрицы.");
            }

            for (int to = 0; to < size; to++)
            {
                JsonNode value = row.path(to);
                if (value.isNull() || !value.isNumber())
                {
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Между некоторыми точками нет автомобильного маршрута. Проверьте координаты.");
                }
                double seconds = value.asDouble();
                matrix[from][to] = from == to ? 0 : Math.max(1, (int) Math.ceil(seconds / 60.0));
            }
        }
        return matrix;
    }

    /**
     * Получает реальную геометрию автомобильного маршрута из OSRM.
     * При ошибке возвращает пустой список, чтобы не отменять
     * уже рассчитанный план оптимизации.
     */
    public List<Coordinate> getRouteGeometry(List<Coordinate> coordinates) {
        if (coordinates == null || coordinates.size() < 2) {
            return List.of();
        }

        // Ограничиваем размер запроса для текущего MVP.
        if (coordinates.size() > 100) {
            log.warn("Слишком много точек для запроса геометрии OSRM: {}",
                    coordinates.size());
            return List.of();
        }

        String joinedCoordinates = coordinates.stream()
                .map(point -> point.longitude() + "," + point.latitude())
                .collect(Collectors.joining(";"));

        String url = baseUrl
                + "/route/v1/"
                + profile
                + "/"
                + joinedCoordinates
                + "?overview=full&geometries=geojson&steps=false";

        try {
            JsonNode json = restTemplate.getForObject(url, JsonNode.class);

            if (json == null || !"Ok".equals(json.path("code").asText())) {
                String error = json == null
                        ? "пустой ответ"
                        : json.path("message").asText("неизвестная ошибка");

                log.warn("OSRM не вернул геометрию маршрута: {}", error);
                return List.of();
            }

            JsonNode routeCoordinates = json
                    .path("routes")
                    .path(0)
                    .path("geometry")
                    .path("coordinates");

            if (!routeCoordinates.isArray() || routeCoordinates.size() < 2) {
                log.warn("OSRM вернул пустую или некорректную геометрию");
                return List.of();
            }

            List<Coordinate> geometry = new ArrayList<>();

            for (JsonNode point : routeCoordinates) {
                if (!point.isArray()
                        || point.size() < 2
                        || !point.path(0).isNumber()
                        || !point.path(1).isNumber()) {
                    log.warn("OSRM вернул некорректную точку геометрии");
                    return List.of();
                }

                double longitude = point.get(0).asDouble();
                double latitude = point.get(1).asDouble();

                geometry.add(new Coordinate(longitude, latitude));
            }

            return List.copyOf(geometry);

        } catch (RestClientException ex) {
            log.warn("Не удалось получить геометрию маршрута из OSRM", ex);
            return List.of();
        }
    }
}
