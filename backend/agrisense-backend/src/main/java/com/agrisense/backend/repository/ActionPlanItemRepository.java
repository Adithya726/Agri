package com.agrisense.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.agrisense.backend.model.ActionPlanItem;

public interface ActionPlanItemRepository
        extends JpaRepository<ActionPlanItem, Long> {
}