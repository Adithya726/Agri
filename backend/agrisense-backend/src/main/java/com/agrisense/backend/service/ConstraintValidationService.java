package com.agrisense.backend.service;

import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;

public interface ConstraintValidationService {

    ConstraintValidationResponse validateAction(
            ConstraintValidationRequest request);

    // Validate against a farm state that is not saved (what-if analysis).
    ConstraintValidationResponse validateAction(
            Long farmId,
            com.agrisense.backend.model.FarmState farmState,
            com.agrisense.backend.model.AgriculturalAction action);
}