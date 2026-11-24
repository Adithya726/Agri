package com.agrisense.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.agrisense.backend.dto.ContextInterpretationResponse.ContextItem;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;

/*
 * Output of the decision engine for one farm: the interpreted context,
 * the risk analysis, the ML results that were available, the resource
 * budget of the farm state, and every evaluated agricultural action
 * ranked with its feasibility decision, reasons, dependencies and
 * position in the sequence.
 */
public record DecisionResponse(
        boolean success,
        String message,
        Long farmId,
        String farmName,
        String crop,
        String growthStage,
        LocalDateTime generatedAt,
        ContextSummary context,
        String overallRisk,
        List<RiskFinding> risks,
        MlSignals ml,
        ResourceSummary resources,
        List<RankedAction> actions,
        List<String> notes
) {

    public record ContextSummary(
            int high,
            int medium,
            int low,
            int unknown,
            List<ContextItem> attentionItems
    ) {}

    public record MlSignals(
            CropSignal cropRecommendation,
            YieldSignal yieldPrediction
    ) {}

    // status is AVAILABLE or UNAVAILABLE; values are null when unavailable.
    public record CropSignal(
            String status,
            String message,
            String recommendedCrop,
            Double topProbability,
            Boolean matchesCurrentCrop
    ) {}

    public record YieldSignal(
            String status,
            String message,
            Double predictedYieldKgPerHa
    ) {}

    public record ResourceSummary(
            Double waterAvailable,
            Integer labourAvailable,
            Double budgetAvailable,
            Double waterAllocated,
            Double budgetAllocated,
            Integer peakLabourAllocated
    ) {}

    public record RankedAction(
            Integer rank,
            Integer sequence,
            Long actionId,
            String actionName,
            String category,
            String decision,
            String validationStatus,
            int priorityScore,
            List<String> addressedRisks,
            List<String> reasons,
            List<Dependency> dependencies,
            List<ConstraintResult> constraints,
            Double requiredWater,
            Integer requiredLabour,
            Double requiredBudget,
            String evidenceStatus,
            String sourceReference
    ) {}

    // type is RESOURCE (shares a consumable with a higher-ranked action)
    // or DATA (needs a missing value before it can be validated).
    public record Dependency(
            String type,
            Long dependsOnActionId,
            String description
    ) {}
}
