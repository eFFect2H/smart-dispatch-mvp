package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.TravelMatrixSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelMatrixSnapshotRepository
        extends JpaRepository<TravelMatrixSnapshotEntity, Short> {
}
