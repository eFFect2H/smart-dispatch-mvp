package com.dispatch.smartdispatch.service;

import com.dispatch.smartdispatch.dto.TeamCreateRequest;
import com.dispatch.smartdispatch.dto.TeamResponse;
import com.dispatch.smartdispatch.entity.Team;
import com.dispatch.smartdispatch.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class TeamService {
    private final TeamRepository repository;

    public TeamService(TeamRepository repository)
    {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> getAll()
    {
        return repository.findAll().stream().map(TeamService::toResponse).toList();
    }

    @Transactional
    public TeamResponse create(TeamCreateRequest request)
    {
        if (request.shiftStartMinute() >= request.shiftEndMinute()) {
            throw new IllegalArgumentException("Начало смены должно быть раньше окончания смены.");
        }

        Team team = new Team();
        team.setName(request.name().trim());
        team.setCapacity(request.capacity());
        team.setStartLatitude(request.startLatitude());
        team.setStartLongitude(request.startLongitude());
        team.setShiftStartMinute(request.shiftStartMinute());
        team.setShiftEndMinute(request.shiftEndMinute());
        team.setActive(request.active());

        return toResponse(repository.save(team));
    }

    public static TeamResponse toResponse(Team team)
    {
        return new TeamResponse(team.getId(), team.getName(), team.getCapacity(),
                team.getStartLatitude(), team.getStartLongitude(), team.getShiftStartMinute(),
                team.getShiftEndMinute(), team.getActive());
    }
}
