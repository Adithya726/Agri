package com.agrisense.backend.service;

import com.agrisense.backend.dto.CropRecommendationResponse;

public interface CropRecommendationService {

    CropRecommendationResponse recommendCrop(Long farmId);

    // Recommendation from the values of a farm state that is not saved (what-if analysis).
    CropRecommendationResponse recommendCrop(Long farmId, com.agrisense.backend.model.FarmState farmState);
}