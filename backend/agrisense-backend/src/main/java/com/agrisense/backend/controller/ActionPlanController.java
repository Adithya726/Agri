package com.agrisense.backend.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.service.ActionPlanService;

@RestController
@RequestMapping("/actionplanapi")
@CrossOrigin(origins = "http://localhost:5173")
public class ActionPlanController {

    private final ActionPlanService actionPlanService;

    public ActionPlanController(ActionPlanService actionPlanService) {
        this.actionPlanService = actionPlanService;
    }

    // Generate a draft action plan for a farm
    @PostMapping("/generate/{farmId}")
    public ResponseEntity<?> generateDraft(@PathVariable Long farmId) {
        try {
            ActionPlanResponse plan =
                    actionPlanService.generateDraft(farmId);

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(plan);

        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Get a specific action plan
    @GetMapping("/{planId}")
    public ResponseEntity<?> getPlanById(@PathVariable Long planId) {
        try {
            return ResponseEntity.ok(
                    actionPlanService.getPlanById(planId)
            );

        } catch (IllegalArgumentException e) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        }
    }

    // Get all action plans for a farm
    @GetMapping("/farm/{farmId}")
    public ResponseEntity<?> getPlansByFarmId(@PathVariable Long farmId) {
        try {
            List<ActionPlanResponse> plans =
                    actionPlanService.getPlansByFarmId(farmId);

            return ResponseEntity.ok(plans);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Farmer accepts or rejects a draft plan
    @PutMapping("/{planId}/review")
    public ResponseEntity<?> reviewPlan(
            @PathVariable Long planId,
            @RequestParam String decision) {

        try {
            return ResponseEntity.ok(
                    actionPlanService.reviewPlan(planId, decision)
            );

        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
