package com.agrisense.backend.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.ContextInterpretationResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.ContextInterpretationService;

@RestController
@RequestMapping("/predictionapi")
@CrossOrigin(origins = "*")
public class ContextInterpretationController {

    private final ContextInterpretationService contextInterpretationService;
    private final OwnershipGuard ownershipGuard;

    public ContextInterpretationController(
            ContextInterpretationService contextInterpretationService,
            OwnershipGuard ownershipGuard) {

        this.contextInterpretationService =
                contextInterpretationService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/context/{farmId}")
    public ContextInterpretationResponse interpretContext(
            @PathVariable Long farmId) {

        ownershipGuard.requireFarm(farmId);

        return contextInterpretationService
                .interpretFarmContext(farmId);
    }
}