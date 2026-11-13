package com.dispatch.smartdispatch.controller.v1;

import com.dispatch.smartdispatch.dto.v1.V1Dtos.DayInputRequest;
import com.dispatch.smartdispatch.dto.v1.V1Dtos.LoadResult;
import com.dispatch.smartdispatch.service.v1.DayInputService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/days")
public class DayInputControllerV1 {
    private final DayInputService dayInputService;

    public DayInputControllerV1(DayInputService dayInputService) {
        this.dayInputService = dayInputService;
    }

    @PutMapping("/{date}")
    public LoadResult replaceDay(@PathVariable LocalDate date,
                                 @Valid @RequestBody DayInputRequest request) {
        return dayInputService.replace(date, request);
    }
}
