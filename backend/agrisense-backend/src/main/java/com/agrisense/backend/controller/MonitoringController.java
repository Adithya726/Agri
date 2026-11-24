package com.agrisense.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.ActionExecutionRequest;
import com.agrisense.backend.dto.MonitoringResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.MonitoringService;

@RestController
@RequestMapping("/monitoringapi")
@CrossOrigin(origins = "http://localhost:5173")
public class MonitoringController {

    private final MonitoringService monitoringService;
    private final OwnershipGuard ownershipGuard;

    public MonitoringController(MonitoringService monitoringService,
                                OwnershipGuard ownershipGuard) {
        this.monitoringService = monitoringService;
        this.ownershipGuard = ownershipGuard;
    }

    // Progress on the latest accepted plan, changes since each action, recent readings.
    @GetMapping("/farm/{farmId}")
    public ResponseEntity<MonitoringResponse> getFarmMonitoring(@PathVariable Long farmId) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.ok(monitoringService.getFarmMonitoring(farmId));
    }

    // Report a plan item as DONE or SKIPPED. Ownership is checked through its plan.
    @PutMapping("/items/{itemId}/execution")
    public ResponseEntity<MonitoringResponse> recordExecution(
            @PathVariable Long itemId,
            @RequestBody ActionExecutionRequest request) {
        return ResponseEntity.ok(monitoringService.recordExecution(itemId, request));
    }

    // Undo a report, returning the item to pending.
    @DeleteMapping("/items/{itemId}/execution")
    public ResponseEntity<MonitoringResponse> clearExecution(@PathVariable Long itemId) {
        return ResponseEntity.ok(monitoringService.clearExecution(itemId));
    }
}
