package com.dispatch.smartdispatch.optimization;

import com.dispatch.smartdispatch.dto.DispatchPlanResponse;
import com.dispatch.smartdispatch.entity.Team;
import com.dispatch.smartdispatch.entity.TransportRequest;
import com.dispatch.smartdispatch.routing.Coordinate;
import com.dispatch.smartdispatch.routing.OsrmMatrixClient;
import com.google.ortools.Loader;
import com.google.ortools.constraintsolver.Assignment;
import com.google.ortools.constraintsolver.FirstSolutionStrategy;
import com.google.ortools.constraintsolver.LocalSearchMetaheuristic;
import com.google.ortools.constraintsolver.RoutingDimension;
import com.google.ortools.constraintsolver.RoutingIndexManager;
import com.google.ortools.constraintsolver.RoutingModel;
import com.google.ortools.constraintsolver.RoutingSearchParameters;
import com.google.ortools.constraintsolver.main;
import com.google.protobuf.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class DispatchOptimizerService
{
    private final OsrmMatrixClient matrixClient;

    public DispatchOptimizerService(OsrmMatrixClient matrixClient)
    {
        this.matrixClient = matrixClient;
    }

    public DispatchPlanResponse optimize(List<Team> teams, List<TransportRequest> requests) {
        if (teams.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Нет активных бригад. Добавьте хотя бы одну активную бригаду.");
        }
        if (requests.isEmpty()) {
            List<DispatchPlanResponse.RoutePlan> emptyRoutes = teams.stream()
                    .map(team -> new DispatchPlanResponse.RoutePlan(
                            team.getId(),
                            team.getName(),
                            team.getStartLatitude(),
                            team.getStartLongitude(),
                            team.getShiftStartMinute(),
                            team.getShiftStartMinute(),
                            0,
                            List.of(),
                            List.of()
                    ))
                    .toList();

            return new DispatchPlanResponse(0, 0, emptyRoutes);
        }

        Loader.loadNativeLibraries();
        int vehicleCount = teams.size();
        int requestCount = requests.size();
        int nodeCount = vehicleCount + requestCount;

        List<Coordinate> coordinates = new ArrayList<>(nodeCount);
        for (Team team : teams) {
            coordinates.add(new Coordinate(team.getStartLongitude(), team.getStartLatitude()));
        }
        for (TransportRequest request : requests) {
            coordinates.add(new Coordinate(request.getLongitude(), request.getLatitude()));
        }
        int[][] travelMinutes = matrixClient.getTravelTimeMinutes(coordinates);

        int[] starts = new int[vehicleCount];
        int[] ends = new int[vehicleCount];
        for (int v = 0; v < vehicleCount; v++) {
            starts[v] = v;
            ends[v] = v;
        }

        RoutingIndexManager manager = new RoutingIndexManager(nodeCount, vehicleCount, starts, ends);
        RoutingModel routing = new RoutingModel(manager);

        int travelCallback = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return travelMinutes[fromNode][toNode];
        });
        routing.setArcCostEvaluatorOfAllVehicles(travelCallback);

        int[] serviceMinutes = new int[nodeCount];
        long[] demands = new long[nodeCount];
        for (int i = 0; i < requestCount; i++) {
            int node = vehicleCount + i;
            serviceMinutes[node] = requests.get(i).getServiceDurationMinutes();
            demands[node] = requests.get(i).getDemand();
        }

        int timeCallback = routing.registerTransitCallback((long fromIndex, long toIndex) -> {
            int fromNode = manager.indexToNode(fromIndex);
            int toNode = manager.indexToNode(toIndex);
            return (long) travelMinutes[fromNode][toNode] + serviceMinutes[fromNode];
        });
        routing.addDimension(timeCallback, 24 * 60, 24 * 60, false, "Time");
        RoutingDimension timeDimension = routing.getMutableDimension("Time");

        for (int i = 0; i < requestCount; i++) {
            TransportRequest request = requests.get(i);
            long index = manager.nodeToIndex(vehicleCount + i);
            timeDimension.cumulVar(index).setRange(request.getWindowStartMinute(), request.getWindowEndMinute());
        }
        for (int v = 0; v < vehicleCount; v++) {
            long start = routing.start(v);
            long end = routing.end(v);
            timeDimension.cumulVar(start).setRange(teams.get(v).getShiftStartMinute(), teams.get(v).getShiftStartMinute());
            timeDimension.cumulVar(end).setRange(teams.get(v).getShiftStartMinute(), teams.get(v).getShiftEndMinute());
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(start));
            routing.addVariableMinimizedByFinalizer(timeDimension.cumulVar(end));
        }

        int demandCallback = routing.registerUnaryTransitCallback((long fromIndex) -> {
            int node = manager.indexToNode(fromIndex);
            return demands[node];
        });
        long[] vehicleCapacities = teams.stream().mapToLong(Team::getCapacity).toArray();
        routing.addDimensionWithVehicleCapacity(demandCallback, 0, vehicleCapacities, true, "Capacity");

        RoutingSearchParameters parameters = main.defaultRoutingSearchParameters().toBuilder()
                .setFirstSolutionStrategy(FirstSolutionStrategy.Value.PARALLEL_CHEAPEST_INSERTION)
                .setLocalSearchMetaheuristic(LocalSearchMetaheuristic.Value.GUIDED_LOCAL_SEARCH)
                .setTimeLimit(Duration.newBuilder().setSeconds(5).build())
                .build();
        Assignment solution = routing.solveWithParameters(parameters);
        if (solution == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Не удалось построить допустимый план. Проверьте временные окна, смены и вместимость бригад.");
        }

        List<DispatchPlanResponse.RoutePlan> routes = new ArrayList<>();
        int assignedRequests = 0;
        int totalTravelMinutes = 0;
        for (int v = 0; v < vehicleCount; v++) {
            Team team = teams.get(v);

            List<DispatchPlanResponse.StopPlan> stops = new ArrayList<>();

            // Координаты точек для OSRM Route API.
            // Coordinate хранит longitude, latitude.
            List<Coordinate> routeCoordinates = new ArrayList<>();

            routeCoordinates.add(new Coordinate(
                    team.getStartLongitude(),
                    team.getStartLatitude()
            ));

            long index = routing.start(v);
            int currentNode = manager.indexToNode(index);
            int routeTravelMinutes = 0;

            while (!routing.isEnd(index)) {
                long nextIndex = solution.value(routing.nextVar(index));
                int nextNode = manager.indexToNode(nextIndex);

                routeTravelMinutes += travelMinutes[currentNode][nextNode];

                if (nextNode >= vehicleCount) {
                    int requestIndex = nextNode - vehicleCount;
                    TransportRequest request = requests.get(requestIndex);

                    int arrivalMinute = Math.toIntExact(
                            solution.value(timeDimension.cumulVar(nextIndex))
                    );

                    stops.add(new DispatchPlanResponse.StopPlan(
                            request.getId(),
                            request.getTitle(),
                            request.getAddress(),
                            request.getLatitude(),
                            request.getLongitude(),
                            arrivalMinute,
                            travelMinutes[currentNode][nextNode],
                            request.getServiceDurationMinutes()
                    ));

                    // Добавляем заявку именно в том порядке,
                    // который вычислил оптимизатор.
                    routeCoordinates.add(new Coordinate(
                            request.getLongitude(),
                            request.getLatitude()
                    ));

                    assignedRequests++;
                }

                currentNode = nextNode;
                index = nextIndex;
            }

            int finishMinute = Math.toIntExact(
                    solution.value(timeDimension.cumulVar(routing.end(v)))
            );

            List<DispatchPlanResponse.RoutePoint> geometry = List.of();

            if (!stops.isEmpty()) {
                // В данной модели автомобиль возвращается на базу.
                routeCoordinates.add(new Coordinate(
                        team.getStartLongitude(),
                        team.getStartLatitude()
                ));

                geometry = matrixClient.getRouteGeometry(routeCoordinates)
                        .stream()
                        .map(point -> new DispatchPlanResponse.RoutePoint(
                                point.latitude(),
                                point.longitude()
                        ))
                        .toList();
            }

            routes.add(new DispatchPlanResponse.RoutePlan(
                    team.getId(),
                    team.getName(),
                    team.getStartLatitude(),
                    team.getStartLongitude(),
                    team.getShiftStartMinute(),
                    finishMinute,
                    routeTravelMinutes,
                    List.copyOf(stops),
                    geometry
            ));

            totalTravelMinutes += routeTravelMinutes;
        }
        if (assignedRequests != requestCount) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Внутренняя ошибка: оптимизатор назначил не все заявки.");
        }
        return new DispatchPlanResponse(assignedRequests, totalTravelMinutes, List.copyOf(routes));
    }
}
