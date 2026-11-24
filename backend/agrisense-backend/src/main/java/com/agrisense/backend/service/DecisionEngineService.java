package com.agrisense.backend.service;

import com.agrisense.backend.dto.DecisionResponse;

public interface DecisionEngineService {

    DecisionResponse decide(Long farmId);
}
