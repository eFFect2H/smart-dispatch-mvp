package com.dispatch.smartdispatch.repository;

import com.dispatch.smartdispatch.entity.RequestStatus;
import com.dispatch.smartdispatch.entity.TransportRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface TransportRequestRepository extends JpaRepository<TransportRequest, Long> {
    List<TransportRequest> findByStatusInOrderByIdAsc(Collection<RequestStatus> statuses);
}
