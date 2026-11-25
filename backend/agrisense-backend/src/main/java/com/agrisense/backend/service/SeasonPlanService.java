package com.agrisense.backend.service;

import com.agrisense.backend.dto.SeasonPlanResponse;

public interface SeasonPlanService {

    SeasonPlanResponse buildSeasonPlan(Long farmId);
}
