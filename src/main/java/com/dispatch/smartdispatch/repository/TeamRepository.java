package com.dispatch.smartdispatch.repository;

import com.dispatch.smartdispatch.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long>
{
    List<Team> findByActiveTrueOrderByIdAsc();
}
