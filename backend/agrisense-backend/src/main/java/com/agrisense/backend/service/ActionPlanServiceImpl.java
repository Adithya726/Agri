package com.agrisense.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ActionPlanItemResponse;
import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

@Service
public class ActionPlanServiceImpl implements ActionPlanService {

    private final ActionPlanRepository actionPlanRepository;
    private final AgriculturalActionRepository agriculturalActionRepository;
    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final ConstraintValidationService constraintValidationService;

    public ActionPlanServiceImpl(
            ActionPlanRepository actionPlanRepository,
            AgriculturalActionRepository agriculturalActionRepository,
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            ConstraintValidationService constraintValidationService) {

        this.actionPlanRepository = actionPlanRepository;
        this.agriculturalActionRepository = agriculturalActionRepository;
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.constraintValidationService = constraintValidationService;
    }

    // Generate a draft action plan for a farm
    @Override
    @Transactional
    public ActionPlanResponse generateDraft(Long farmId) {

        if (farmId == null) {
            throw new IllegalArgumentException("Farm ID is required.");
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Farm not found."));

        FarmState farmState = farmStateRepository.findByFarm(farm)
                .orElseThrow(() ->
                        new IllegalStateException(
                                "Farm state not found. Update the farm state first."));

        if (farmState.getCrop() == null
                || farmState.getCrop().trim().isEmpty()) {

            throw new IllegalStateException(
                    "Current crop is unavailable. Update the farm state first.");
        }

        // Select active actions that match the farm's crop and growth stage.
        // Exclude actions without a reference or with the known placeholder.
        List<AgriculturalAction> actions =
                agriculturalActionRepository.findByActiveTrue()
                        .stream()
                        .filter(action ->
                                action.getApplicableCrop() != null
                                && action.getApplicableGrowthStage() != null)
                        .filter(action ->
                                action.getApplicableCrop().trim()
                                        .equalsIgnoreCase(
                                                farmState.getCrop().trim()))
                        .filter(action ->
                                action.getApplicableGrowthStage().trim()
                                        .equalsIgnoreCase(
                                                farmState.getGrowthStage() == null
                                                        ? ""
                                                        : farmState.getGrowthStage().trim()))
                        .filter(action ->
                                "REFERENCE_SUPPORTED".equalsIgnoreCase(
                                        action.getEvidenceStatus())
                                && action.getSourceReference() != null
                                && !action.getSourceReference().trim().isEmpty()
                                && !action.getSourceReference()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(
                                                "use the actual agricultural reference"))
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        ArrayList::new));

        ActionPlan plan = new ActionPlan();
        plan.setFarm(farm);
        plan.setStatus("DRAFT");
        plan.setCreatedAt(LocalDateTime.now());

        // Validate each eligible action against the current farm state.
        for (AgriculturalAction action : actions) {

            ConstraintValidationRequest request =
                    new ConstraintValidationRequest();

            request.setFarmId(farmId);
            request.setActionId(action.getId());

            ConstraintValidationResponse validation =
                    constraintValidationService.validateAction(request);

            ActionPlanItem item = new ActionPlanItem();
            item.setAgriculturalAction(action);
            item.setValidationStatus(validation.getOverallStatus());
            item.setValidationMessage(validation.getMessage());

            plan.addItem(item);
        }

        // Prioritize feasible actions, followed by unknown and infeasible actions.
        plan.getItems().sort(
                Comparator.comparingInt(
                        item -> statusPriority(item.getValidationStatus()))
        );

        int sequence = 1;

        for (ActionPlanItem item : plan.getItems()) {
            item.setSequenceOrder(sequence++);
        }

        ActionPlan savedPlan = actionPlanRepository.save(plan);

        return convertToResponse(savedPlan);
    }

    // Assign priority based on validation status.
    private int statusPriority(String status) {

        if ("FEASIBLE".equalsIgnoreCase(status)) {
            return 1;
        }

        if ("UNKNOWN".equalsIgnoreCase(status)) {
            return 2;
        }

        if ("INFEASIBLE".equalsIgnoreCase(status)) {
            return 3;
        }

        return 4;
    }

    // Get one action plan by ID.
    @Override
    @Transactional(readOnly = true)
    public ActionPlanResponse getPlanById(Long planId) {

        if (planId == null) {
            throw new IllegalArgumentException("Plan ID is required.");
        }

        ActionPlan plan = actionPlanRepository.findById(planId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Action plan not found."));

        return convertToResponse(plan);
    }

    // Get all action plans belonging to a farm.
    @Override
    @Transactional(readOnly = true)
    public List<ActionPlanResponse> getPlansByFarmId(Long farmId) {

        if (farmId == null) {
            throw new IllegalArgumentException("Farm ID is required.");
        }

        return actionPlanRepository
                .findByFarmIdOrderByCreatedAtDesc(farmId)
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    // Accept or reject a draft action plan.
    @Override
    @Transactional
    public ActionPlanResponse reviewPlan(Long planId, String decision) {

        if (planId == null) {
            throw new IllegalArgumentException("Plan ID is required.");
        }

        if (decision == null
                || (!"ACCEPTED".equalsIgnoreCase(decision)
                && !"REJECTED".equalsIgnoreCase(decision))) {

            throw new IllegalArgumentException(
                    "Decision must be ACCEPTED or REJECTED.");
        }

        ActionPlan plan = actionPlanRepository.findById(planId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Action plan not found."));

        if (!"DRAFT".equalsIgnoreCase(plan.getStatus())) {
            throw new IllegalStateException(
                    "Only draft plans can be reviewed.");
        }

        plan.setStatus(decision.toUpperCase(Locale.ROOT));
        plan.setReviewedAt(LocalDateTime.now());

        ActionPlan savedPlan = actionPlanRepository.save(plan);

        return convertToResponse(savedPlan);
    }

    private ActionPlanResponse convertToResponse(ActionPlan plan) {

        List<ActionPlanItemResponse> itemResponses = plan.getItems()
                .stream()
                .map(item -> new ActionPlanItemResponse(
                        item.getId(),
                        item.getAgriculturalAction().getId(),
                        item.getAgriculturalAction().getActionName(),
                        item.getAgriculturalAction().getCategory(),
                        item.getAgriculturalAction().getDescription(),
                        item.getSequenceOrder(),
                        item.getValidationStatus(),
                        item.getValidationMessage()
                ))
                .toList();

        return new ActionPlanResponse(
                plan.getId(),
                plan.getFarm().getId(),
                plan.getFarm().getFarmName(),
                plan.getFarm().getCrop(),
                plan.getFarm().getGrowthStage(),
                plan.getStatus(),
                plan.getCreatedAt(),
                plan.getReviewedAt(),
                itemResponses
        );
    }
}