package com.agrisense.backend.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/*
 * Monitoring view of one farm: progress on its latest accepted plan,
 * what changed in the recorded farm state after each completed action,
 * and the recent readings. Changes are reported as recorded values and
 * differences only; no agronomic judgement is added here.
 */
public record MonitoringResponse(
        Long farmId,
        String farmName,
        String crop,
        String growthStage,
        PlanSummary plan,
        Progress progress,
        List<MonitoredItem> items,
        List<Reading> readings,
        String message
) {

    public record PlanSummary(
            Long id,
            String status,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt
    ) {}

    public record Progress(int total, int done, int skipped, int pending) {}

    public record MonitoredItem(
            Long itemId,
            Integer sequenceOrder,
            String actionName,
            String category,
            String decision,
            Execution execution,
            ChangeSince change
    ) {}

    public record Execution(
            String status,
            LocalDate executedOn,
            String note,
            LocalDateTime recordedAt
    ) {}

    public record ChangeSince(
            LocalDateTime baselineRecordedAt,
            LocalDateTime latestRecordedAt,
            List<FieldChange> changes,
            String message
    ) {}

    public record FieldChange(
            String field,
            String before,
            String after,
            Double difference
    ) {}

    public record Reading(
            Long id,
            LocalDateTime recordedAt,
            String crop,
            String growthStage,
            Double soilMoisture,
            Double soilPh,
            Double nitrogen,
            Double phosphorus,
            Double potassium,
            Double temperature,
            Double humidity,
            Double rainfall,
            Double waterAvailable,
            Integer labourAvailable,
            Double budgetAvailable,
            String cropHealth,
            String diseaseStatus
    ) {}
}
