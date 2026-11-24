package com.agrisense.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ActionPlanItemResponse;
import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
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

    private final RiskAnalysisService riskAnalysisService;

    private final ActionPrioritizer actionPrioritizer;

    public ActionPlanServiceImpl(
            ActionPlanRepository actionPlanRepository,
            AgriculturalActionRepository agriculturalActionRepository,
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            ConstraintValidationService constraintValidationService,
            RiskAnalysisService riskAnalysisService,
            ActionPrioritizer actionPrioritizer) {

        this.actionPlanRepository = actionPlanRepository;
        this.agriculturalActionRepository = agriculturalActionRepository;
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.constraintValidationService = constraintValidationService;

        this.riskAnalysisService = riskAnalysisService;

        this.actionPrioritizer = actionPrioritizer;
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
                        .filter(ActionPrioritizer::isReferenceSupported)
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        ArrayList::new));

        // Nothing sourced applies: say so instead of saving an empty plan.
        if (actions.isEmpty()) {
            throw new IllegalStateException(
                    "No reference-supported agricultural actions are available for "
                            + farmState.getCrop().trim() + " at growth stage "
                            + (farmState.getGrowthStage() == null
                                    || farmState.getGrowthStage().trim().isEmpty()
                                    ? "(not set)"
                                    : farmState.getGrowthStage().trim())
                            + ". No plan was created.");
        }

        ActionPlan plan = new ActionPlan();
        plan.setFarm(farm);
        plan.setStatus("DRAFT");
        plan.setCreatedAt(LocalDateTime.now());

        // Validate each eligible action against the current farm state.
        List<ActionPrioritizer.Candidate> candidates = new ArrayList<>();

        for (AgriculturalAction action : actions) {

            ConstraintValidationRequest request =
                    new ConstraintValidationRequest();

            request.setFarmId(farmId);
            request.setActionId(action.getId());

            ConstraintValidationResponse validation =
                    constraintValidationService.validateAction(request);

            candidates.add(new ActionPrioritizer.Candidate(action, validation));
        }

        // Order with the decision engine: feasible actions that fit the
        // farm's combined water and budget first (higher risk relevance
        // first), then deferred, unknown and infeasible ones. Each item
        // keeps its validation status and gets the specific reasons.
        ActionPrioritizer.Result ranking = actionPrioritizer.prioritize(
                candidates,
                riskAnalysisService.analyzeFarmRisks(farmId),
                farmState);

        Map<Long, AgriculturalAction> byId = new HashMap<>();
        actions.forEach(action -> byId.put(action.getId(), action));

        int sequence = 1;

        for (RankedAction ranked : ranking.actions()) {

            ActionPlanItem item = new ActionPlanItem();
            item.setAgriculturalAction(byId.get(ranked.actionId()));
            item.setValidationStatus(ranked.validationStatus());
            item.setValidationMessage(planMessage(ranked));
            item.setSequenceOrder(sequence++);

            plan.addItem(item);
        }

        ActionPlan savedPlan = actionPlanRepository.save(plan);

        return convertToResponse(savedPlan);
    }

    // Decision and reasons, within the item's 1000-character column.
    private String planMessage(RankedAction ranked) {

        String text = ranked.decision() + ": "
                + String.join(" ", ranked.reasons());

        return text.length() <= 1000 ? text : text.substring(0, 997) + "...";
    }

    // planMessage stores "DECISION: reasons"; read the decision back.
    // Items saved before the decision engine have no such prefix.
    static String decisionOf(String validationMessage) {

        if (validationMessage == null) {
            return null;
        }

        int colon = validationMessage.indexOf(':');

        if (colon <= 0) {
            return null;
        }

        String prefix = validationMessage.substring(0, colon).trim();

        return switch (prefix) {
            case ActionPrioritizer.RECOMMENDED,
                    ActionPrioritizer.DEFERRED,
                    ActionPrioritizer.NEEDS_DATA,
                    ActionPrioritizer.REJECTED -> prefix;
            default -> null;
        };
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
                ).withEvidence(
                        decisionOf(item.getValidationMessage()),
                        item.getAgriculturalAction().getEvidenceStatus(),
                        item.getAgriculturalAction().getSourceReference()
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