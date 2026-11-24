package com.agrisense.backend.service;

import com.agrisense.backend.dto.ReplanResponse;

public interface ReplanService {

    ReplanResponse.Assessment assess(Long farmId);

    ReplanResponse.Comparison replan(Long farmId);

    ReplanResponse.Comparison compare(Long currentPlanId, Long newPlanId);
}
