package com.agrisense.backend.controller;

import com.agrisense.backend.dto.YieldPredictionResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.YieldPredictionService;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/predictionapi")
@CrossOrigin(origins = "*")
public class YieldPredictionController {

    private final YieldPredictionService yieldPredictionService;
    private final OwnershipGuard ownershipGuard;

    public YieldPredictionController(
            YieldPredictionService yieldPredictionService,
            OwnershipGuard ownershipGuard
    ) {
        this.yieldPredictionService =
                yieldPredictionService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/yield/{farmId}")
    public YieldPredictionResponse predictYield(
            @PathVariable Long farmId
    ) {

        ownershipGuard.requireFarm(farmId);

        return yieldPredictionService.predictYield(
                farmId
        );
    }
}