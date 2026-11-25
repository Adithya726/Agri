package com.agrisense.backend.dto;

import java.util.List;

/*
 * A rule-based answer built only from the farm's stored data and the
 * existing services. intent is one of NEXT_ACTION, WHY_REJECTED,
 * PLAN_PROGRESS, RISKS, WHAT_CHANGED, CURRENT_STATE or UNKNOWN.
 * answered is false when the data does not cover the question.
 */
public record AssistantResponse(
        Long farmId,
        String question,
        String intent,
        boolean answered,
        String answer,
        List<String> facts,
        List<String> sources,
        List<String> suggestions
) {}
