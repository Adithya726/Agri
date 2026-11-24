package com.agrisense.backend.controller;

import com.agrisense.backend.dto.AgriculturalActionRequest;
import com.agrisense.backend.dto.AgriculturalActionResponse;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.AgriculturalActionService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/agricultureactionapi")
@CrossOrigin(origins = "*")
public class AgriculturalActionController {

    private final AgriculturalActionService service;
    private final OwnershipGuard ownershipGuard;

    public AgriculturalActionController(
            AgriculturalActionService service,
            OwnershipGuard ownershipGuard) {

        this.service = service;
        this.ownershipGuard = ownershipGuard;
    }

    @PostMapping("/add")
    public ResponseEntity<AgriculturalActionResponse> addAction(
            @RequestBody AgriculturalActionRequest request) {

        // Actions are shared reference data used for every farm's
        // decisions and plans, so only an ADMIN may add them.
        ownershipGuard.requireAdmin();

        return ResponseEntity.ok(
                service.addAction(request)
        );
    }

    @GetMapping("/viewall")
    public ResponseEntity<List<AgriculturalAction>> getAllActions() {

        return ResponseEntity.ok(
                service.getAllActions()
        );
    }

    @GetMapping("/active")
    public ResponseEntity<List<AgriculturalAction>> getActiveActions() {

        return ResponseEntity.ok(
                service.getActiveActions()
        );
    }

    @GetMapping("/view/{id}")
    public ResponseEntity<AgriculturalAction> getActionById(
            @PathVariable Long id) {

        AgriculturalAction action =
                service.getActionById(id);

        if (action == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(action);
    }

    @GetMapping("/farm")
    public ResponseEntity<List<AgriculturalAction>> getActionsForFarm(
            @RequestParam String crop,
            @RequestParam(required = false) String growthStage) {

        return ResponseEntity.ok(
                service.getActionsForFarm(
                        crop,
                        growthStage
                )
        );
    }
}