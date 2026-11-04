package com.agrisense.backend.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.agrisense.backend.model.ActionPlan;

public interface ActionPlanRepository
        extends JpaRepository<ActionPlan, Long> {

    List<ActionPlan> findByFarmIdOrderByCreatedAtDesc(Long farmId);
}