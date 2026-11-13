package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.DispatchDaySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface DispatchDaySnapshotRepository
        extends JpaRepository<DispatchDaySnapshotEntity, LocalDate> {
}
