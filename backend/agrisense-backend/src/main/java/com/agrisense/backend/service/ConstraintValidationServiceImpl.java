
package com.agrisense.backend.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

@Service
public class ConstraintValidationServiceImpl
        implements ConstraintValidationService {

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final AgriculturalActionRepository agriculturalActionRepository;

    public ConstraintValidationServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            AgriculturalActionRepository agriculturalActionRepository) {

        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.agriculturalActionRepository = agriculturalActionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintValidationResponse validateAction(
            ConstraintValidationRequest request) {

        // 1. Validate the request.
        if (request == null
                || request.getFarmId() == null
                || request.getActionId() == null) {

            return failureResponse(
                    request != null ? request.getFarmId() : null,
                    "Farm ID and Action ID are required.");
        }

        // 2. Find the farm.
        Farm farm = farmRepository
                .findById(request.getFarmId())
                .orElse(null);

        if (farm == null) {
            return failureResponse(
                    request.getFarmId(),
                    "Farm not found.");
        }

        // 3. Find the latest stored farm state.
        FarmState farmState = farmStateRepository
                .findByFarm(farm)
                .orElse(null);

        if (farmState == null) {
            return failureResponse(
                    farm.getId(),
                    "Farm state not found. Update the farm state before validation.");
        }

        // 4. Find the agricultural action.
        AgriculturalAction action = agriculturalActionRepository
                .findById(request.getActionId())
                .orElse(null);

        if (action == null) {
            return failureResponse(
                    farm.getId(),
                    "Agricultural action not found.");
        }

        return evaluate(farm.getId(), farmState, action);
    }

    /*
     * Validate an action against a farm state that is not saved
     * (what-if analysis). Same checks as validateAction.
     */
    @Override
    public ConstraintValidationResponse validateAction(
            Long farmId, FarmState farmState, AgriculturalAction action) {

        if (farmState == null || action == null) {
            return failureResponse(farmId, "Farm state and action are required.");
        }
        return evaluate(farmId, farmState, action);
    }

    private ConstraintValidationResponse evaluate(
            Long farmId, FarmState farmState, AgriculturalAction action) {

        // 5. Do not validate inactive actions.
        if (!Boolean.TRUE.equals(action.getActive())) {
            return failureResponse(
                    farmId,
                    "This agricultural action is inactive and cannot be validated.");
        }

        List<ConstraintResult> constraints = new ArrayList<>();

        // 6. Validate crop and growth-stage applicability independently.
        validateCropApplicability(action, farmState, constraints);
        validateGrowthStageApplicability(action, farmState, constraints);

        // 7. Validate resource requirements.
        validateWater(action, farmState, constraints);
        validateLabour(action, farmState, constraints);
        validateBudget(action, farmState, constraints);

        // 8. Determine the overall result.
        String overallStatus = determineOverallStatus(constraints);

        String message;

        if ("FEASIBLE".equals(overallStatus)) {
            message = "This step fits your crop, stage and resources.";
        } else if ("INFEASIBLE".equals(overallStatus)) {
            message = "One or more constraints are not satisfied.";
        } else {
            message = "Some of your farm details are missing. Add them on the Farm State page.";
        }

        return new ConstraintValidationResponse(
                true,
                message,
                farmId,
                action.getActionName(),
                overallStatus,
                constraints);
    }

    private void validateCropApplicability(
            AgriculturalAction action,
            FarmState farmState,
            List<ConstraintResult> constraints) {

        String requiredCrop = normalize(action.getApplicableCrop());
        String currentCrop = normalize(farmState.getCrop());

        if (requiredCrop == null) {
            constraints.add(new ConstraintResult(
                    "CROP_APPLICABILITY",
                    "NEEDS_INPUT",
                    "This step has no crop set. An admin must correct the action data."));
            return;
        }

        if (currentCrop == null) {
            constraints.add(new ConstraintResult(
                    "CROP_APPLICABILITY",
                    "NEEDS_INPUT",
                    "Add your current crop on the Farm State page."));
            return;
        }

        if (requiredCrop.equalsIgnoreCase(currentCrop)) {
            constraints.add(new ConstraintResult(
                    "CROP_APPLICABILITY",
                    "FEASIBLE",
                    "The action matches the farm's current crop."));
        } else {
            constraints.add(new ConstraintResult(
                    "CROP_APPLICABILITY",
                    "INFEASIBLE",
                    "This action is intended for " + requiredCrop
                            + ", but the farm currently has " + currentCrop + "."));
        }
    }

    private void validateGrowthStageApplicability(
            AgriculturalAction action,
            FarmState farmState,
            List<ConstraintResult> constraints) {

        String requiredStage = normalize(action.getApplicableGrowthStage());
        String currentStage = normalize(farmState.getGrowthStage());

        if (requiredStage == null) {
            constraints.add(new ConstraintResult(
                    "GROWTH_STAGE_APPLICABILITY",
                    "NEEDS_INPUT",
                    "This step has no growth stage set. An admin must correct the action data."));
            return;
        }

        if (currentStage == null) {
            constraints.add(new ConstraintResult(
                    "GROWTH_STAGE_APPLICABILITY",
                    "NEEDS_INPUT",
                    "Add your crop's growth stage on the Farm State page."));
            return;
        }

        if (requiredStage.equalsIgnoreCase(currentStage)) {
            constraints.add(new ConstraintResult(
                    "GROWTH_STAGE_APPLICABILITY",
                    "FEASIBLE",
                    "The action matches the farm's current growth stage."));
        } else {
            constraints.add(new ConstraintResult(
                    "GROWTH_STAGE_APPLICABILITY",
                    "INFEASIBLE",
                    "This action is intended for growth stage "
                            + requiredStage + ", but the farm is at "
                            + currentStage + "."));
        }
    }

    private void validateWater(
            AgriculturalAction action,
            FarmState farmState,
            List<ConstraintResult> constraints) {

        Double required = action.getRequiredWater();
        Double available = farmState.getWaterAvailable();

        if (required != null && !isValidRequirement(required)) {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "NEEDS_INPUT",
                    "The stored water requirement is invalid. Correct the action data."));
            return;
        }

        if (required == null) {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "FIELD_GUIDE",
                    "The ANGRAU guide gives no separate water quantity for this step. Follow the amount written in the step itself."));
            return;
        }

        if (available == null) {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "NEEDS_INPUT",
                    "Add how much water you have on the Farm State page so this can be checked."));
            return;
        }

        if (!Double.isFinite(available) || available < 0) {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "NEEDS_INPUT",
                    "Your saved water value is not valid. Correct it on the Farm State page."));
            return;
        }

        if (available >= required) {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "FEASIBLE",
                    "Available water meets the action requirement."));
        } else {
            constraints.add(new ConstraintResult(
                    "WATER",
                    "INFEASIBLE",
                    "Insufficient water. Required: " + required
                            + "; available: " + available + "."));
        }
    }

    private void validateLabour(
            AgriculturalAction action,
            FarmState farmState,
            List<ConstraintResult> constraints) {

        Integer required = action.getRequiredLabour();
        Integer available = farmState.getLabourAvailable();

        if (required != null && required < 0) {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "NEEDS_INPUT",
                    "The stored labour requirement is invalid. Correct the action data."));
            return;
        }

        if (required == null) {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "FIELD_GUIDE",
                    "The ANGRAU guide gives no separate labour count for this step. Plan labour by your field size."));
            return;
        }

        if (available == null) {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "NEEDS_INPUT",
                    "Add how many workers you have on the Farm State page so this can be checked."));
            return;
        }

        if (available < 0) {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "NEEDS_INPUT",
                    "Your saved labour value is not valid. Correct it on the Farm State page."));
            return;
        }

        if (available >= required) {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "FEASIBLE",
                    "Available labour meets the action requirement."));
        } else {
            constraints.add(new ConstraintResult(
                    "LABOUR",
                    "INFEASIBLE",
                    "Insufficient labour. Required: " + required
                            + "; available: " + available + "."));
        }
    }

    private void validateBudget(
            AgriculturalAction action,
            FarmState farmState,
            List<ConstraintResult> constraints) {

        Double required = action.getRequiredBudget();
        Double available = farmState.getBudgetAvailable();

        if (required != null && !isValidRequirement(required)) {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "NEEDS_INPUT",
                    "The stored budget requirement is invalid. Correct the action data."));
            return;
        }

        if (required == null) {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "FIELD_GUIDE",
                    "The ANGRAU guide gives no separate cost for this step. Price the inputs named in the step at your local shop."));
            return;
        }

        if (available == null) {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "NEEDS_INPUT",
                    "Add your available budget on the Farm State page so this can be checked."));
            return;
        }

        if (!Double.isFinite(available) || available < 0) {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "NEEDS_INPUT",
                    "Your saved budget value is not valid. Correct it on the Farm State page."));
            return;
        }

        if (available >= required) {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "FEASIBLE",
                    "Available budget meets the action requirement."));
        } else {
            constraints.add(new ConstraintResult(
                    "BUDGET",
                    "INFEASIBLE",
                    "Insufficient budget. Required: " + required
                            + "; available: " + available + "."));
        }
    }

    private boolean isValidRequirement(Double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String determineOverallStatus(
            List<ConstraintResult> constraints) {

        boolean needsInput = false;

        for (ConstraintResult constraint : constraints) {
            if ("INFEASIBLE".equalsIgnoreCase(constraint.getStatus())) {
                // A confirmed violation takes precedence over missing inputs.
                return "INFEASIBLE";
            }

            // FIELD_GUIDE means the source sets no separate figure for
            // this resource; it does not block the step.
            if ("NEEDS_INPUT".equalsIgnoreCase(constraint.getStatus())) {
                needsInput = true;
            }
        }

        return needsInput ? "NEEDS_INPUT" : "FEASIBLE";
    }

    private ConstraintValidationResponse failureResponse(
            Long farmId,
            String message) {

        return new ConstraintValidationResponse(
                false,
                message,
                farmId,
                null,
                "NOT_CHECKED",
                new ArrayList<>());
    }
}
