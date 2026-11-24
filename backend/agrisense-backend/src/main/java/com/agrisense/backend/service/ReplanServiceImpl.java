package com.agrisense.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.PlanSummary;
import com.agrisense.backend.dto.ReplanResponse.Assessment;
import com.agrisense.backend.dto.ReplanResponse.ComparedAction;
import com.agrisense.backend.dto.ReplanResponse.Comparison;
import com.agrisense.backend.dto.ReplanResponse.ItemImpact;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.security.OwnershipGuard;

/*
 * Dynamic replanning on top of the existing pieces:
 *  - change detection: farm state readings (Phase H) from when the
 *    accepted plan was made, against the newest reading;
 *  - impact analysis and reassessment: each pending action of that plan
 *    is validated again (ConstraintValidationService) and ranked again
 *    (ActionPrioritizer) on today's state, and compared with its stored
 *    decision;
 *  - new plan: ActionPlanService.generateDraft, unchanged;
 *  - comparison: actions kept, added and removed, with their decisions.
 * Accepting or rejecting the new plan uses the existing review endpoint.
 */
@Service
public class ReplanServiceImpl implements ReplanService {

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final FarmStateReadingRepository readingRepository;
    private final ActionPlanRepository actionPlanRepository;
    private final ActionExecutionRepository executionRepository;
    private final AgriculturalActionRepository actionRepository;
    private final ConstraintValidationService constraintValidationService;
    private final RiskAnalysisService riskAnalysisService;
    private final ActionPrioritizer actionPrioritizer;
    private final ActionPlanService actionPlanService;
    private final OwnershipGuard ownershipGuard;

    public ReplanServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            FarmStateReadingRepository readingRepository,
            ActionPlanRepository actionPlanRepository,
            ActionExecutionRepository executionRepository,
            AgriculturalActionRepository actionRepository,
            ConstraintValidationService constraintValidationService,
            RiskAnalysisService riskAnalysisService,
            ActionPrioritizer actionPrioritizer,
            ActionPlanService actionPlanService,
            OwnershipGuard ownershipGuard) {
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.readingRepository = readingRepository;
        this.actionPlanRepository = actionPlanRepository;
        this.executionRepository = executionRepository;
        this.actionRepository = actionRepository;
        this.constraintValidationService = constraintValidationService;
        this.riskAnalysisService = riskAnalysisService;
        this.actionPrioritizer = actionPrioritizer;
        this.actionPlanService = actionPlanService;
        this.ownershipGuard = ownershipGuard;
    }

    // ------------------------------------------------------------------
    // Change detection, impact analysis, reassessment
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Assessment assess(Long farmId) {

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        FarmState state = farmStateRepository.findByFarm(farm)
                .orElseThrow(() -> new InvalidRequestException(
                        "Farm state not found. Update the farm state first."));

        ActionPlan plan = latestAccepted(farmId);

        if (plan == null) {
            return new Assessment(farmId, null, null, null, null, List.of(), false, false,
                    List.of(), 0, false,
                    List.of("There is no accepted plan to reassess. Accept a plan on the Action Plan page first."));
        }

        // Change detection
        List<FarmStateReading> readings = readingRepository.findByFarmIdOrderByRecordedAtDesc(farmId);
        FarmStateReading latest = readings.isEmpty() ? null : readings.get(0);

        FarmStateReading baseline = plan.getCreatedAt() == null ? null : readingRepository
                .findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(farmId, plan.getCreatedAt())
                .orElse(null);

        String baselineNote = null;
        if (baseline == null && !readings.isEmpty()) {
            baseline = readings.get(readings.size() - 1);
            baselineNote = "No reading was saved before this plan was created, so changes are measured"
                    + " from the earliest reading.";
        }

        List<FieldChange> changes = baseline == null || latest == null
                || Objects.equals(baseline.getId(), latest.getId())
                ? List.of()
                : MonitoringServiceImpl.compare(baseline, latest);

        boolean cropChanged = changes.stream().anyMatch(c -> "crop".equals(c.field()));
        boolean stageChanged = changes.stream().anyMatch(c -> "growthStage".equals(c.field()));

        // Reassess the actions not yet reported
        Map<Long, ActionExecution> executions = executionsOf(plan.getId());
        List<ActionPlanItem> items = sortedItems(plan);

        List<ActionPrioritizer.Candidate> candidates = new ArrayList<>();
        for (ActionPlanItem item : items) {
            if (!executions.containsKey(item.getId())) {
                candidates.add(new ActionPrioritizer.Candidate(
                        item.getAgriculturalAction(), validate(farmId, item.getAgriculturalAction())));
            }
        }

        Map<Long, RankedAction> current = new HashMap<>();
        if (!candidates.isEmpty()) {
            actionPrioritizer.prioritize(candidates, riskAnalysisService.analyzeFarmRisks(farmId), state)
                    .actions()
                    .forEach(r -> current.put(r.actionId(), r));
        }

        List<ItemImpact> impacts = new ArrayList<>();
        int changedCount = 0;

        for (ActionPlanItem item : items) {
            AgriculturalAction action = item.getAgriculturalAction();
            ActionExecution e = executions.get(item.getId());
            String previous = ActionPlanServiceImpl.decisionOf(item.getValidationMessage());

            if (e != null) {
                impacts.add(new ItemImpact(item.getId(), action.getId(), action.getActionName(),
                        action.getCategory(), e.getStatus(), previous, null, false,
                        List.of("Already reported as " + e.getStatus() + ", so it is not reassessed.")));
                continue;
            }

            RankedAction now = current.get(action.getId());
            String nowDecision = now == null ? null : now.decision();
            boolean changed = !Objects.equals(previous, nowDecision);
            if (changed) {
                changedCount++;
            }

            impacts.add(new ItemImpact(item.getId(), action.getId(), action.getActionName(),
                    action.getCategory(), null, previous, nowDecision, changed,
                    now == null ? List.of() : now.reasons()));
        }

        // Sourced actions for today's crop and stage that the plan lacks
        Set<Long> inPlan = items.stream()
                .map(i -> i.getAgriculturalAction().getId())
                .collect(Collectors.toSet());
        int newActions = (int) applicableActions(state).stream()
                .filter(a -> !inPlan.contains(a.getId()))
                .count();

        List<String> reasons = new ArrayList<>();
        if (baselineNote != null) {
            reasons.add(baselineNote);
        }
        if (cropChanged) {
            reasons.add("The crop changed since the plan was made.");
        }
        if (stageChanged) {
            reasons.add("The growth stage changed since the plan was made.");
        }
        if (changedCount > 0) {
            reasons.add(changedCount + " pending action(s) now get a different decision.");
        }
        if (newActions > 0) {
            reasons.add(newActions + " sourced action(s) for the current crop and stage are not in the plan.");
        }

        boolean recommended = cropChanged || stageChanged || changedCount > 0 || newActions > 0;

        if (!recommended) {
            reasons.add(changes.isEmpty()
                    ? "Nothing has changed since the plan was made. The plan still stands."
                    : "The farm state changed, but no pending action's decision changed. The plan still stands.");
        }

        return new Assessment(farmId, summary(plan),
                baseline == null ? null : baseline.getRecordedAt(),
                latest == null ? null : latest.getRecordedAt(),
                baselineNote, changes, cropChanged, stageChanged, impacts, newActions,
                recommended, reasons);
    }

    // ------------------------------------------------------------------
    // New plan and comparison
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public Comparison replan(Long farmId) {

        ActionPlan current = latestAccepted(farmId);

        if (current == null) {
            throw new InvalidRequestException(
                    "There is no accepted plan to replace. Generate and accept a plan on the Action Plan page first.");
        }

        ActionPlanResponse draft;
        try {
            draft = actionPlanService.generateDraft(farmId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }

        return compare(current.getId(), draft.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Comparison compare(Long currentPlanId, Long newPlanId) {

        if (currentPlanId == null || newPlanId == null) {
            throw new InvalidRequestException("Both plan IDs are required.");
        }
        if (currentPlanId.equals(newPlanId)) {
            throw new InvalidRequestException("Choose two different plans to compare.");
        }

        ActionPlan current = ownershipGuard.requireActionPlan(currentPlanId);
        ActionPlan proposed = ownershipGuard.requireActionPlan(newPlanId);

        if (!Objects.equals(farmIdOf(current), farmIdOf(proposed))) {
            throw new InvalidRequestException("Both plans must belong to the same farm.");
        }

        Map<Long, ActionExecution> executions = executionsOf(current.getId());

        Map<Long, ActionPlanItem> before = byAction(current);
        Map<Long, ActionPlanItem> after = byAction(proposed);

        List<ComparedAction> kept = new ArrayList<>();
        List<ComparedAction> removed = new ArrayList<>();
        List<ComparedAction> added = new ArrayList<>();

        for (Map.Entry<Long, ActionPlanItem> entry : before.entrySet()) {
            ActionPlanItem b = entry.getValue();
            ActionPlanItem a = after.get(entry.getKey());
            ActionExecution e = executions.get(b.getId());
            ComparedAction row = compared(b, a, e);
            (a == null ? removed : kept).add(row);
        }
        for (Map.Entry<Long, ActionPlanItem> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) {
                added.add(compared(null, entry.getValue(), null));
            }
        }

        long decisionChanges = kept.stream()
                .filter(k -> !Objects.equals(k.currentDecision(), k.newDecision()))
                .count();
        long removedDone = removed.stream()
                .filter(r -> ActionExecution.DONE.equals(r.executionStatus()))
                .count();

        List<String> summary = new ArrayList<>();
        summary.add(kept.size() + " action(s) kept"
                + (decisionChanges > 0 ? ", " + decisionChanges + " with a different decision." : "."));
        summary.add(added.size() + " action(s) added.");
        summary.add(removed.size() + " action(s) dropped"
                + (removedDone > 0 ? ", " + removedDone + " of them already done." : "."));
        if (!"DRAFT".equalsIgnoreCase(proposed.getStatus())) {
            summary.add("The new plan is " + proposed.getStatus().toLowerCase(Locale.ROOT)
                    + ", so it can no longer be reviewed.");
        }

        return new Comparison(summary(current), summary(proposed), kept, added, removed, summary);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ActionPlan latestAccepted(Long farmId) {
        return actionPlanRepository.findByFarmIdOrderByCreatedAtDesc(farmId).stream()
                .filter(p -> "ACCEPTED".equalsIgnoreCase(p.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private ConstraintValidationResponse validate(Long farmId, AgriculturalAction action) {
        ConstraintValidationRequest request = new ConstraintValidationRequest();
        request.setFarmId(farmId);
        request.setActionId(action.getId());
        return constraintValidationService.validateAction(request);
    }

    // Same selection ActionPlanServiceImpl.generateDraft makes.
    private List<AgriculturalAction> applicableActions(FarmState state) {
        String crop = state.getCrop() == null ? "" : state.getCrop().trim();
        String stage = state.getGrowthStage() == null ? "" : state.getGrowthStage().trim();
        return actionRepository.findByActiveTrue().stream()
                .filter(a -> a.getApplicableCrop() != null && a.getApplicableGrowthStage() != null)
                .filter(a -> a.getApplicableCrop().trim().equalsIgnoreCase(crop))
                .filter(a -> a.getApplicableGrowthStage().trim().equalsIgnoreCase(stage))
                .filter(ActionPrioritizer::isReferenceSupported)
                .toList();
    }

    private Map<Long, ActionExecution> executionsOf(Long planId) {
        Map<Long, ActionExecution> map = new HashMap<>();
        for (ActionExecution e : executionRepository.findByActionPlanItemActionPlanId(planId)) {
            map.put(e.getActionPlanItem().getId(), e);
        }
        return map;
    }

    private static List<ActionPlanItem> sortedItems(ActionPlan plan) {
        List<ActionPlanItem> items = new ArrayList<>(plan.getItems());
        items.sort(Comparator.comparing(ActionPlanItem::getSequenceOrder,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return items;
    }

    private static Map<Long, ActionPlanItem> byAction(ActionPlan plan) {
        Map<Long, ActionPlanItem> map = new LinkedHashMap<>();
        for (ActionPlanItem item : sortedItems(plan)) {
            map.putIfAbsent(item.getAgriculturalAction().getId(), item);
        }
        return map;
    }

    private static ComparedAction compared(ActionPlanItem before, ActionPlanItem after, ActionExecution e) {
        AgriculturalAction action = (before != null ? before : after).getAgriculturalAction();
        return new ComparedAction(
                action.getId(),
                action.getActionName(),
                action.getCategory(),
                before == null ? null : before.getSequenceOrder(),
                after == null ? null : after.getSequenceOrder(),
                before == null ? null : ActionPlanServiceImpl.decisionOf(before.getValidationMessage()),
                after == null ? null : ActionPlanServiceImpl.decisionOf(after.getValidationMessage()),
                e == null ? null : e.getStatus());
    }

    private static Long farmIdOf(ActionPlan plan) {
        return plan.getFarm() == null ? null : plan.getFarm().getId();
    }

    private static PlanSummary summary(ActionPlan plan) {
        return new PlanSummary(plan.getId(), plan.getStatus(), plan.getCreatedAt(), plan.getReviewedAt());
    }
}
