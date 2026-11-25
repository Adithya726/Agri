package com.agrisense.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/*
 * Whole-season action plan for one farm, written for farmers.
 *
 * status is one of:
 *  ON_TRACK     - nothing needs attention; follow this stage's steps.
 *  ACT_NOW      - a risk or a change in the farm's readings needs action.
 *  ADD_DETAILS  - farm details are missing, so some steps cannot be checked.
 *
 * Every step comes from the ANGRAU package of practices stored in the
 * agricultural actions table, with its source. requirements gives the
 * amounts, cost, workers and water for this farm's area from sourced
 * figures; a part with no source is marked not sourced. Nothing is estimated.
 */
public record SeasonPlanResponse(
        Long farmId,
        String farmName,
        String location,
        String crop,
        String variety,
        String growthStage,
        String season,
        LocalDateTime generatedAt,
        String status,
        String headline,
        List<Step> doNow,
        List<Step> onHold,
        List<Alert> alerts,
        List<Change> changes,
        LocalDateTime changesSince,
        List<String> missingDetails,
        List<StagePlan> stages,
        List<String> notes
) {

    // One step to follow. why is plain language; checks are the
    // constraint results the step passed or failed.
    public record Step(
            Integer order,
            Long actionId,
            String name,
            String category,
            String instruction,
            String decision,
            List<String> why,
            List<ConstraintResult> checks,
            String source,
            StepRequirements requirements
    ) {}

    // A reading that needs attention, what it means, and what to do.
    public record Alert(
            String severity,
            String topic,
            String reading,
            String meaning,
            List<String> doThis
    ) {}

    public record Change(
            String field,
            String label,
            String before,
            String after,
            String sentence
    ) {}

    // status is DONE, NOW or UPCOMING.
    public record StagePlan(
            String stage,
            String status,
            List<StageStep> steps
    ) {}

    public record StageStep(
            Long actionId,
            String name,
            String category,
            String instruction,
            boolean completed,
            String source,
            StepRequirements requirements
    ) {}
}
