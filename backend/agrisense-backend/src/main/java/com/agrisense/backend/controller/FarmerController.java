package com.agrisense.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.agrisense.backend.dto.FarmerResponse;
import com.agrisense.backend.dto.FarmerUpdateRequest;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.security.OwnershipGuard;
import com.agrisense.backend.service.FarmerService;

@RestController
@RequestMapping("/farmerapi")
@CrossOrigin(origins = "*")
public class FarmerController {

    private final FarmerService farmerService;
    private final OwnershipGuard ownershipGuard;

    public FarmerController(FarmerService farmerService,
                            OwnershipGuard ownershipGuard) {
        this.farmerService = farmerService;
        this.ownershipGuard = ownershipGuard;
    }

    @GetMapping("/{id}")
    public ResponseEntity<FarmerResponse> getFarmer(
            @PathVariable Long id
    ) {

        ownershipGuard.requireFarmer(id);

        FarmerResponse farmer =
                farmerService.getFarmerById(id);

        if (farmer == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .build();
        }

        return ResponseEntity.ok(farmer);
    }

    @PutMapping("/update/{id}")
    public ResponseEntity<FarmerResponse> updateFarmer(
            @PathVariable Long id,
            @RequestBody FarmerUpdateRequest request
    ) {

        ownershipGuard.requireFarmer(id);

        if (request == null
                || request.getName() == null
                || request.getName().isBlank()) {
            throw new InvalidRequestException("Name is required");
        }

        FarmerResponse farmer =
                farmerService.updateFarmer(id, request);

        if (farmer == null) {
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .build();
        }

        return ResponseEntity.ok(farmer);
    }
}