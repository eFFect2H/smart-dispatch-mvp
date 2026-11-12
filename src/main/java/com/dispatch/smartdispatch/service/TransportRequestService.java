package com.dispatch.smartdispatch.service;

import com.dispatch.smartdispatch.dto.TransportRequestCreateRequest;
import com.dispatch.smartdispatch.dto.TransportRequestResponse;
import com.dispatch.smartdispatch.entity.RequestStatus;
import com.dispatch.smartdispatch.entity.TransportRequest;
import com.dispatch.smartdispatch.repository.TransportRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class TransportRequestService {
    private final TransportRequestRepository repository;

    public TransportRequestService(TransportRequestRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<TransportRequestResponse> getAll() {
        return repository.findAll().stream().map(TransportRequestService::toResponse).toList();
    }

    @Transactional
    public TransportRequestResponse create(TransportRequestCreateRequest request) {
        if (request.windowStartMinute() >= request.windowEndMinute()) {
            throw new IllegalArgumentException("Начало временного окна должно быть раньше его окончания.");
        }
        TransportRequest entity = new TransportRequest();
        entity.setTitle(request.title().trim());
        entity.setAddress(request.address().trim());
        entity.setLatitude(request.latitude());
        entity.setLongitude(request.longitude());
        entity.setWindowStartMinute(request.windowStartMinute());
        entity.setWindowEndMinute(request.windowEndMinute());
        entity.setServiceDurationMinutes(request.serviceDurationMinutes());
        entity.setDemand(request.demand());
        entity.setStatus(RequestStatus.NEW);
        return toResponse(repository.save(entity));
    }

    public static TransportRequestResponse toResponse(TransportRequest request) {
        return new TransportRequestResponse(request.getId(), request.getTitle(), request.getAddress(),
                request.getLatitude(), request.getLongitude(), request.getWindowStartMinute(),
                request.getWindowEndMinute(), request.getServiceDurationMinutes(), request.getDemand(),
                request.getStatus(), request.getAssignedTeamId(), request.getPlannedArrivalMinute(),
                request.getCreatedAt());
    }
}
