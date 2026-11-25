package com.agrisense.backend.dto;

import java.time.LocalDate;
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
        List<String> notes,
        CropDays cropDays
) {

    // The farm's sowing and transplanting dates and how many days have
    // passed since each, counted on today's date in India. Null parts
    // mean the farmer has not added that date.
    public record CropDays(
            LocalDate sowingDate,
            Integer daysAfterSowing,
            LocalDate transplantingDate,
            Integer daysAfterTransplanting
    ) {}

    // When a step falls due on this farm, worked out from the guide's
    // day count and the farm's sowing or transplanting date.
    // status is one of:
    //  DUE_NOW      - today is inside the step's window;
    //  COMING       - the window starts later (from is its first day);
    //  PASSED       - the window has ended;
    //  STARTED      - the window has started and the guide gives no end;
    //  ADD_DATE     - the farm's sowing or transplanting date is missing;
    //  BY_STAGE     - the guide gives a crop stage, not a day count.
    public record Due(
            String status,
            LocalDate from,
            LocalDate to,
            String sentence
    ) {}

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
            StepRequirements requirements,
            Due due
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
            StepRequirements requirements,
            Due due
    ) {}
}
