package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Optional;

public interface DispatchDaySnapshotRepository
        extends JpaRepository<DispatchDaySnapshotEntity, LocalDate> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select day from DispatchDaySnapshotEntity day where day.dayDate = :date")
    Optional<DispatchDaySnapshotEntity> findByDateForUpdate(@Param("date") LocalDate date);
}
