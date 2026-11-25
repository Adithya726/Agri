package com.agrisense.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.WhatIfRequest;
import com.agrisense.backend.dto.WhatIfResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.WhatIfService;

@RestController
@RequestMapping("/whatifapi")
@CrossOrigin(origins = "http://localhost:5173")
public class WhatIfController {

    private final WhatIfService whatIfService;
    private final OwnershipGuard ownershipGuard;

    public WhatIfController(WhatIfService whatIfService, OwnershipGuard ownershipGuard) {
        this.whatIfService = whatIfService;
        this.ownershipGuard = ownershipGuard;
    }

    // Run the pipeline on an edited copy of the farm state. Nothing is saved.
    @PostMapping("/farm/{farmId}")
    public ResponseEntity<WhatIfResponse> analyze(
            @PathVariable Long farmId,
            @RequestBody(required = false) WhatIfRequest request) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.ok(whatIfService.analyze(farmId, request));
    }
}
