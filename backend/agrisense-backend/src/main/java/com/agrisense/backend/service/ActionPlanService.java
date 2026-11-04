package com.agrisense.backend.service;

import java.util.List;

import com.agrisense.backend.dto.ActionPlanResponse;

public interface ActionPlanService {

    ActionPlanResponse generateDraft(Long farmId);

    ActionPlanResponse getPlanById(Long planId);

    List<ActionPlanResponse> getPlansByFarmId(Long farmId);

    ActionPlanResponse reviewPlan(Long planId, String decision);

}
