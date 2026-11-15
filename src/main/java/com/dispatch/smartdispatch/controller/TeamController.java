package com.dispatch.smartdispatch.controller;

import com.dispatch.smartdispatch.dto.TeamCreateRequest;
import com.dispatch.smartdispatch.dto.TeamResponse;
import com.dispatch.smartdispatch.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController
{
    private final TeamService service;

    public TeamController(TeamService service)
    {
        this.service = service;
    }

    @GetMapping
    public List<TeamResponse> getAll()
    {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@Valid @RequestBody TeamCreateRequest request)
    {
        return service.create(request);
    }
}
