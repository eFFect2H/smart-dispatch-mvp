package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.DispatchEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DispatchEventRepository extends JpaRepository<DispatchEventEntity, Long> {
    Optional<DispatchEventEntity> findByDayDateAndEventId(LocalDate dayDate, String eventId);
    List<DispatchEventEntity> findAllByDayDateOrderByEventSequenceAsc(LocalDate dayDate);
    long deleteByDayDate(LocalDate dayDate);
}
