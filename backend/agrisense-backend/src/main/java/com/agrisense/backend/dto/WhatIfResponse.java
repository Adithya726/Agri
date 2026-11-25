package com.agrisense.backend.dto;

import java.util.List;

import com.agrisense.backend.dto.DecisionResponse.CropSignal;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.DecisionResponse.ResourceSummary;
import com.agrisense.backend.dto.DecisionResponse.YieldSignal;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.Reading;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;

/*
 * The real pipeline run twice: on the saved farm state and on an
 * edited copy that is never saved.
 */
public record WhatIfResponse(
        Long farmId,
        String farmName,
        String crop,
        List<FieldChange> changedFields,
        Scenario current,
        Scenario whatIf,
        List<ActionDifference> actionDifferences,
        YieldSignal yieldPrediction,
        List<String> notes
) {

    public record Scenario(
            Reading state,
            String overallRisk,
            List<RiskFinding> risks,
            int evaluatedActions,
            int recommended,
            int deferred,
            int needsData,
            int rejected,
            List<RankedAction> actions,
            ResourceSummary resources,
            CropSignal cropRecommendation
    ) {}

    // An action whose decision differs between the two runs, or that
    // applies in only one of them (decision null where it does not apply).
    public record ActionDifference(
            Long actionId,
            String actionName,
            String currentDecision,
            String whatIfDecision
    ) {}
}
