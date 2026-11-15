package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.ReferenceSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferenceSnapshotRepository
        extends JpaRepository<ReferenceSnapshotEntity, Short> {
}
