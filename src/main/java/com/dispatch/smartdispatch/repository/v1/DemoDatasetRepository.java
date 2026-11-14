package com.dispatch.smartdispatch.repository.v1;

import com.dispatch.smartdispatch.entity.v1.DemoDatasetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DemoDatasetRepository extends JpaRepository<DemoDatasetEntity, Short> {
}
