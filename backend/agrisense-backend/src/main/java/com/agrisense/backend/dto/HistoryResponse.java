package com.agrisense.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.agrisense.backend.dto.MonitoringResponse.FieldChange;

/*
 * One farm's history, newest first, built from the stored records:
 * farm state readings, action plans (created, accepted, rejected,
 * superseded) and the done/skipped reports on plan actions.
 * ML predictions are not stored, so they are not part of it.
 */
public record HistoryResponse(
        Long farmId,
        String farmName,
        List<String> appliedTypes,
        LocalDate from,
        LocalDate to,
        int total,
        Map<String, Integer> countsByType,
        List<HistoryEvent> events,
        List<String> notes
) {

    /*
     * type: STATE_READING, PLAN_CREATED, PLAN_ACCEPTED, PLAN_REJECTED,
     * PLAN_SUPERSEDED, ACTION_DONE, ACTION_SKIPPED.
     * timeInferred is true when the stored data has no exact timestamp
     * for the event (a plan's superseded time is not stored).
     */
    public record HistoryEvent(
            String type,
            String group,
            LocalDateTime at,
            boolean timeInferred,
            String title,
            String detail,
            Long planId,
            Long readingId,
            Long itemId,
            String actionName,
            String category,
            LocalDate executedOn,
            String note,
            List<FieldChange> changes
    ) {}
}
