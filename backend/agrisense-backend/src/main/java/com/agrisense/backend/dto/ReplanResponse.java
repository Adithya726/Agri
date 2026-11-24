package com.agrisense.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.PlanSummary;

/*
 * Dynamic replanning: what changed since the accepted plan was made,
 * which of its pending actions that affects, and how a new plan differs.
 */
public final class ReplanResponse {

    private ReplanResponse() {}

    public record Assessment(
            Long farmId,
            PlanSummary plan,
            LocalDateTime baselineRecordedAt,
            LocalDateTime latestRecordedAt,
            String baselineNote,
            List<FieldChange> changes,
            boolean cropChanged,
            boolean stageChanged,
            List<ItemImpact> impacts,
            int newActionsAvailable,
            boolean replanRecommended,
            List<String> reasons
    ) {}

    // previousDecision: stored when the plan was made.
    // currentDecision: the same validation and ranking run on today's state.
    public record ItemImpact(
            Long itemId,
            Long actionId,
            String actionName,
            String category,
            String executionStatus,
            String previousDecision,
            String currentDecision,
            boolean changed,
            List<String> currentReasons
    ) {}

    public record Comparison(
            PlanSummary currentPlan,
            PlanSummary newPlan,
            List<ComparedAction> kept,
            List<ComparedAction> added,
            List<ComparedAction> removed,
            List<String> summary
    ) {}

    public record ComparedAction(
            Long actionId,
            String actionName,
            String category,
            Integer currentSequence,
            Integer newSequence,
            String currentDecision,
            String newDecision,
            String executionStatus
    ) {}
}
