package com.agrisense.backend.controller;

import com.agrisense.backend.dto.DiseaseDetectionResponse;
import com.agrisense.backend.service.DiseaseDetectionService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.agrisense.backend.security.OwnershipGuard;

@RestController
@RequestMapping("/predictionapi")
@CrossOrigin(origins = "*")
public class DiseaseDetectionController {

    private final DiseaseDetectionService diseaseDetectionService;
    private final OwnershipGuard ownershipGuard;

    public DiseaseDetectionController(
            DiseaseDetectionService diseaseDetectionService,
            OwnershipGuard ownershipGuard
    ) {
        this.diseaseDetectionService = diseaseDetectionService;
        this.ownershipGuard = ownershipGuard;
    }

    @PostMapping(
            value = "/disease-detection/{farmId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DiseaseDetectionResponse detectDisease(
            @PathVariable Long farmId,
            @RequestParam("image") MultipartFile image
    ) {
        ownershipGuard.requireFarm(farmId);

        return diseaseDetectionService.detectDisease(
                farmId,
                image
        );
    }
}