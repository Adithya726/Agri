package com.agrisense.backend.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.agrisense.backend.model.ActionExecution;

public interface ActionExecutionRepository
        extends JpaRepository<ActionExecution, Long> {

    Optional<ActionExecution> findByActionPlanItemId(Long itemId);

    List<ActionExecution> findByActionPlanItemActionPlanId(Long planId);

    List<ActionExecution> findByActionPlanItemActionPlanFarmId(Long farmId);
}
