package com.agrisense.backend.service;

import java.util.List;

import com.agrisense.backend.dto.ActionPlanResponse;

public interface ActionPlanService {

    ActionPlanResponse generateDraft(Long farmId);

    ActionPlanResponse getPlanById(Long planId);

    // Latest plan, or a fresh draft when the farm state changed since.
    ActionPlanResponse currentPlan(Long farmId);

    List<ActionPlanResponse> getPlansByFarmId(Long farmId);

    ActionPlanResponse reviewPlan(Long planId, String decision);

}
