package com.agrisense.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.AssistantRequest;
import com.agrisense.backend.dto.AssistantResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.FarmAssistantService;

@RestController
@RequestMapping("/assistantapi")
@CrossOrigin(origins = "http://localhost:5173")
public class FarmAssistantController {

    private final FarmAssistantService assistantService;
    private final OwnershipGuard ownershipGuard;

    public FarmAssistantController(FarmAssistantService assistantService, OwnershipGuard ownershipGuard) {
        this.assistantService = assistantService;
        this.ownershipGuard = ownershipGuard;
    }

    // Answer a question about one farm from its stored data.
    @PostMapping("/farm/{farmId}")
    public ResponseEntity<AssistantResponse> ask(
            @PathVariable Long farmId,
            @RequestBody(required = false) AssistantRequest request) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.ok(assistantService.ask(farmId,
                request == null ? null : request.getQuestion()));
    }
}
