package com.agrisense.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.ReplanResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.ReplanService;

/*
 * Accepting or rejecting the new plan uses PUT /actionplanapi/{planId}/review.
 */
@RestController
@RequestMapping("/replanapi")
@CrossOrigin(origins = "http://localhost:5173")
public class ReplanController {

    private final ReplanService replanService;
    private final OwnershipGuard ownershipGuard;

    public ReplanController(ReplanService replanService, OwnershipGuard ownershipGuard) {
        this.replanService = replanService;
        this.ownershipGuard = ownershipGuard;
    }

    // What changed since the accepted plan, and what it does to its pending actions.
    @GetMapping("/farm/{farmId}/assessment")
    public ResponseEntity<ReplanResponse.Assessment> assess(@PathVariable Long farmId) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.ok(replanService.assess(farmId));
    }

    // Generate a new draft from today's state and compare it with the accepted plan.
    @PostMapping("/farm/{farmId}")
    public ResponseEntity<ReplanResponse.Comparison> replan(@PathVariable Long farmId) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.status(HttpStatus.CREATED).body(replanService.replan(farmId));
    }

    // Compare any two plans of the same farm. Ownership is checked per plan.
    @GetMapping("/compare")
    public ResponseEntity<ReplanResponse.Comparison> compare(
            @RequestParam Long currentPlanId,
            @RequestParam Long newPlanId) {
        return ResponseEntity.ok(replanService.compare(currentPlanId, newPlanId));
    }
}
