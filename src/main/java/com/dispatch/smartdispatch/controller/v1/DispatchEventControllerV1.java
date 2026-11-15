package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.EventDtos.EventAcceptedResponse;
import com.dispatch.smartdispatch.dto.v1.EventDtos.EventHistoryResponse;
import com.dispatch.smartdispatch.dto.v1.EventDtos.EventRequest;
import com.dispatch.smartdispatch.service.v1.DispatchEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/days/{date}/events")
public class DispatchEventControllerV1 {
    private final DispatchEventService eventService;

    public DispatchEventControllerV1(DispatchEventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public EventAcceptedResponse append(@PathVariable LocalDate date,
                                        @Valid @RequestBody EventRequest request) {
        return eventService.append(date, request);
    }

    @GetMapping
    public EventHistoryResponse history(@PathVariable LocalDate date) {
        return eventService.getHistory(date);
    }
}
