package com.dispatch.smartdispatch.controller;

import com.dispatch.smartdispatch.dto.TransportRequestCreateRequest;
import com.dispatch.smartdispatch.dto.TransportRequestResponse;
import com.dispatch.smartdispatch.service.TransportRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/requests")
public class TransportRequestController {
    private final TransportRequestService service;

    public TransportRequestController(TransportRequestService service)
    {
        this.service = service;
    }

    @GetMapping
    public List<TransportRequestResponse> getAll()
    {
        return service.getAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransportRequestResponse create(@Valid @RequestBody TransportRequestCreateRequest request) {
        return service.create(request);
    }
}
