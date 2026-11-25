
package com.agrisense.backend.service;

import com.agrisense.backend.dto.RiskAnalysisResponse;

public interface RiskAnalysisService {

    RiskAnalysisResponse analyzeFarmRisks(Long farmId);

    // Risk analysis of a farm state that is not saved (what-if analysis).
    RiskAnalysisResponse analyzeFarmRisks(
            com.agrisense.backend.model.Farm farm,
            com.agrisense.backend.model.FarmState farmState);

}
