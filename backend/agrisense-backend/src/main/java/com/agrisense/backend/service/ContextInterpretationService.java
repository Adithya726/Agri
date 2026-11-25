package com.agrisense.backend.service;

import com.agrisense.backend.dto.ContextInterpretationResponse;

public interface ContextInterpretationService {

    ContextInterpretationResponse interpretFarmContext(Long farmId);

    // Interpret a farm state that is not saved (what-if analysis).
    ContextInterpretationResponse interpretFarmContext(
            com.agrisense.backend.model.Farm farm,
            com.agrisense.backend.model.FarmState farmState);

}