package com.agrisense.backend.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agrisense.backend.dto.HistoryResponse;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.HistoryService;

@RestController
@RequestMapping("/historyapi")
@CrossOrigin(origins = "http://localhost:5173")
public class HistoryController {

    private final HistoryService historyService;
    private final OwnershipGuard ownershipGuard;

    public HistoryController(HistoryService historyService, OwnershipGuard ownershipGuard) {
        this.historyService = historyService;
        this.ownershipGuard = ownershipGuard;
    }

    // Timeline of one farm. type: STATE, PLAN, ACTION or exact event types (comma separated).
    // from/to: yyyy-MM-dd, inclusive.
    @GetMapping("/farm/{farmId}")
    public ResponseEntity<HistoryResponse> getFarmHistory(
            @PathVariable Long farmId,
            @RequestParam(required = false) List<String> type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        ownershipGuard.requireFarm(farmId);
        return ResponseEntity.ok(historyService.getFarmHistory(farmId, type, from, to));
    }
}
