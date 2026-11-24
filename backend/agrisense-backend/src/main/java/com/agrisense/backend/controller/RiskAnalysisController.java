
package com.agrisense.backend.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.RiskAnalysisService;

@RestController
@RequestMapping("/predictionapi")
@CrossOrigin(origins = "*")
public class RiskAnalysisController {

    private final RiskAnalysisService riskAnalysisService;
    private final OwnershipGuard ownershipGuard;

    public RiskAnalysisController(
            RiskAnalysisService riskAnalysisService,
            OwnershipGuard ownershipGuard) {
        this.riskAnalysisService = riskAnalysisService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/risk-analysis/{farmId}")
    public RiskAnalysisResponse analyzeFarmRisks(
            @PathVariable Long farmId) {
        ownershipGuard.requireFarm(farmId);
        return riskAnalysisService.analyzeFarmRisks(farmId);
    }
}
