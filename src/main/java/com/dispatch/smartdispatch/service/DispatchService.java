package com.dispatch.smartdispatch.service;

import com.dispatch.smartdispatch.dto.DispatchPlanResponse;
import com.dispatch.smartdispatch.entity.RequestStatus;
import com.dispatch.smartdispatch.entity.Team;
import com.dispatch.smartdispatch.entity.TransportRequest;
import com.dispatch.smartdispatch.optimization.DispatchOptimizerService;
import com.dispatch.smartdispatch.repository.TeamRepository;
import com.dispatch.smartdispatch.repository.TransportRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DispatchService
{
    private final TeamRepository teamRepository;
    private final TransportRequestRepository requestRepository;
    private final DispatchOptimizerService optimizer;

    public DispatchService(TeamRepository teamRepository,
                           TransportRequestRepository requestRepository,
                           DispatchOptimizerService optimizer)
    {
        this.teamRepository = teamRepository;
        this.requestRepository = requestRepository;
        this.optimizer = optimizer;
    }

    @Transactional
    public DispatchPlanResponse optimize()
    {
        List<Team> teams = teamRepository.findByActiveTrueOrderByIdAsc();

        List<TransportRequest> requests = requestRepository.findByStatusInOrderByIdAsc(
                EnumSet.of(RequestStatus.NEW, RequestStatus.PLANNED));

        if (teams.isEmpty() && !requests.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Нельзя распределить заявки: нет активных бригад.");
        }

        DispatchPlanResponse plan = optimizer.optimize(teams, requests);
        Map<Long, TransportRequest> requestsById = new HashMap<>();

        for (TransportRequest request : requests) requestsById.put(request.getId(), request);

        for (DispatchPlanResponse.RoutePlan route : plan.routes()) {
            for (DispatchPlanResponse.StopPlan stop : route.stops()) {
                TransportRequest request = requestsById.get(stop.requestId());
                if (request == null) continue;
                request.setStatus(RequestStatus.PLANNED);
                request.setAssignedTeamId(route.teamId());
                request.setPlannedArrivalMinute(stop.arrivalMinute());
            }
        }
        requestRepository.saveAll(requests);
        return plan;
    }
}
