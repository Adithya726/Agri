package com.agrisense.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.DecisionResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.DecisionEngineService;

@RestController
@RequestMapping("/decisionapi")
public class DecisionEngineController {

    private final DecisionEngineService decisionEngineService;
    private final OwnershipGuard ownershipGuard;

    public DecisionEngineController(
            DecisionEngineService decisionEngineService,
            OwnershipGuard ownershipGuard) {
        this.decisionEngineService = decisionEngineService;
        this.ownershipGuard = ownershipGuard;
    }

    // Ranked, sequenced actions for a farm with the evidence behind them.
    @GetMapping("/farm/{farmId}")
    public DecisionResponse decide(@PathVariable Long farmId) {
        ownershipGuard.requireFarm(farmId);
        return decisionEngineService.decide(farmId);
    }
}
