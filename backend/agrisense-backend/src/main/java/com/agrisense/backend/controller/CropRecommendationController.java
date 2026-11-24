package com.agrisense.backend.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.CropRecommendationResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.CropRecommendationService;

@RestController
@RequestMapping("/predictionapi")
@CrossOrigin(origins = "*")
public class CropRecommendationController {

    private final CropRecommendationService cropRecommendationService;
    private final OwnershipGuard ownershipGuard;

    public CropRecommendationController(
            CropRecommendationService cropRecommendationService,
            OwnershipGuard ownershipGuard) {

        this.cropRecommendationService =
                cropRecommendationService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/crop-recommendation/{farmId}")
    public CropRecommendationResponse recommendCrop(
            @PathVariable Long farmId) {

        ownershipGuard.requireFarm(farmId);

        return cropRecommendationService
                .recommendCrop(farmId);
    }
}