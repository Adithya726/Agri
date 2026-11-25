package com.agrisense.backend.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.HistoryResponse;
import com.agrisense.backend.dto.HistoryResponse.HistoryEvent;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;

/*
 * Builds one timeline from records that already exist. Nothing is
 * stored by this service.
 *
 *  - farm_state_readings: one STATE_READING per saved state, with the
 *    values that changed since the reading before it;
 *  - action_plans: PLAN_CREATED (createdAt), PLAN_ACCEPTED or
 *    PLAN_REJECTED (reviewedAt). A SUPERSEDED plan was accepted first;
 *    the time it was superseded is not stored, so it is taken as the
 *    acceptance time of the next accepted plan and marked inferred;
 *  - action_executions: ACTION_DONE / ACTION_SKIPPED with the note.
 */
@Service
public class HistoryServiceImpl implements HistoryService {

    public static final String STATE_READING = "STATE_READING";
    public static final String PLAN_CREATED = "PLAN_CREATED";
    public static final String PLAN_ACCEPTED = "PLAN_ACCEPTED";
    public static final String PLAN_REJECTED = "PLAN_REJECTED";
    public static final String PLAN_SUPERSEDED = "PLAN_SUPERSEDED";
    public static final String ACTION_DONE = "ACTION_DONE";
    public static final String ACTION_SKIPPED = "ACTION_SKIPPED";

    public static final String GROUP_STATE = "STATE";
    public static final String GROUP_PLAN = "PLAN";
    public static final String GROUP_ACTION = "ACTION";

    static final List<String> ALL_TYPES = List.of(STATE_READING, PLAN_CREATED, PLAN_ACCEPTED,
            PLAN_REJECTED, PLAN_SUPERSEDED, ACTION_DONE, ACTION_SKIPPED);

    static final String ML_NOTE = "ML predictions (crop recommendation, yield, disease) are"
            + " calculated on request and not stored, so they are not part of this history.";

    private final FarmRepository farmRepository;
    private final FarmStateReadingRepository readingRepository;
    private final ActionPlanRepository actionPlanRepository;
    private final ActionExecutionRepository executionRepository;

    public HistoryServiceImpl(
            FarmRepository farmRepository,
            FarmStateReadingRepository readingRepository,
            ActionPlanRepository actionPlanRepository,
            ActionExecutionRepository executionRepository) {
        this.farmRepository = farmRepository;
        this.readingRepository = readingRepository;
        this.actionPlanRepository = actionPlanRepository;
        this.executionRepository = executionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryResponse getFarmHistory(Long farmId, List<String> types,
                                          LocalDate from, LocalDate to) {

        Set<String> wanted = resolveTypes(types);

        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("The start date must be on or before the end date.",
                    Map.of("from", "The start date must be on or before the end date."));
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        List<HistoryEvent> events = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        notes.add(ML_NOTE);

        readingEvents(farmId, events);
        boolean inferred = planEvents(farmId, events);
        actionEvents(farmId, events);

        if (inferred) {
            notes.add("A superseded plan's end time is not stored; it is shown at the time the"
                    + " plan that replaced it was accepted.");
        }

        List<HistoryEvent> filtered = events.stream()
                .filter(e -> wanted.contains(e.type()))
                .filter(e -> from == null || !eventDate(e).isBefore(from))
                .filter(e -> to == null || !eventDate(e).isAfter(to))
                .sorted(Comparator.comparing(HistoryEvent::at).reversed()
                        .thenComparing(e -> ALL_TYPES.indexOf(e.type()), Comparator.reverseOrder()))
                .toList();

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String type : ALL_TYPES) {
            counts.put(type, 0);
        }
        filtered.forEach(e -> counts.merge(e.type(), 1, Integer::sum));

        return new HistoryResponse(farm.getId(), farm.getFarmName(), List.copyOf(wanted),
                from, to, filtered.size(), counts, filtered, notes);
    }

    // Accepts exact event types and the groups STATE, PLAN, ACTION.
    static Set<String> resolveTypes(List<String> types) {
        Set<String> result = new LinkedHashSet<>();
        if (types == null) {
            result.addAll(ALL_TYPES);
            return result;
        }
        List<String> invalid = new ArrayList<>();
        for (String raw : types) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split(",")) {
                String t = part.trim().toUpperCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                switch (t) {
                    case GROUP_STATE -> result.add(STATE_READING);
                    case GROUP_PLAN -> result.addAll(List.of(PLAN_CREATED, PLAN_ACCEPTED,
                            PLAN_REJECTED, PLAN_SUPERSEDED));
                    case GROUP_ACTION -> result.addAll(List.of(ACTION_DONE, ACTION_SKIPPED));
                    default -> {
                        if (ALL_TYPES.contains(t)) {
                            result.add(t);
                        } else {
                            invalid.add(part.trim());
                        }
                    }
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new InvalidRequestException("Unknown history type: " + String.join(", ", invalid)
                    + ". Use STATE, PLAN, ACTION or one of " + String.join(", ", ALL_TYPES) + ".",
                    Map.of("type", "Unknown history type: " + String.join(", ", invalid)));
        }
        if (result.isEmpty()) {
            result.addAll(ALL_TYPES);
        }
        return result;
    }

    private void readingEvents(Long farmId, List<HistoryEvent> events) {
        List<FarmStateReading> readings = new ArrayList<>(
                readingRepository.findByFarmIdOrderByRecordedAtDesc(farmId));
        readings.sort(Comparator.comparing(FarmStateReading::getRecordedAt));
        FarmStateReading previous = null;
        for (FarmStateReading r : readings) {
            List<FieldChange> changes = previous == null
                    ? List.of()
                    : MonitoringServiceImpl.compare(previous, r);
            String detail;
            if (previous == null) {
                detail = "First recorded farm state: " + describe(r) + ".";
            } else if (changes.isEmpty()) {
                detail = "Saved again with no value changed.";
            } else {
                detail = changes.size() + " value(s) changed since the previous reading.";
            }
            events.add(new HistoryEvent(STATE_READING, GROUP_STATE, r.getRecordedAt(), false,
                    "Farm state recorded", detail, null, r.getId(), null, null, null, null, null,
                    changes));
            previous = r;
        }
    }

    // Returns true when a superseded time had to be inferred.
    private boolean planEvents(Long farmId, List<HistoryEvent> events) {
        List<ActionPlan> plans = actionPlanRepository.findByFarmIdOrderByCreatedAtDesc(farmId);
        boolean inferred = false;
        for (ActionPlan plan : plans) {
            String status = plan.getStatus() == null ? "" : plan.getStatus().toUpperCase(Locale.ROOT);
            int items = plan.getItems() == null ? 0 : plan.getItems().size();
            String counts = items + " action(s)" + decisionCounts(plan);

            if (plan.getCreatedAt() != null) {
                events.add(planEvent(PLAN_CREATED, plan.getCreatedAt(), false,
                        "Plan #" + plan.getId() + " created",
                        "Draft plan with " + counts + ".", plan));
            }
            if (plan.getReviewedAt() == null) {
                continue;
            }
            if ("REJECTED".equals(status)) {
                events.add(planEvent(PLAN_REJECTED, plan.getReviewedAt(), false,
                        "Plan #" + plan.getId() + " rejected",
                        "You rejected this draft plan.", plan));
            } else if ("ACCEPTED".equals(status) || "SUPERSEDED".equals(status)) {
                events.add(planEvent(PLAN_ACCEPTED, plan.getReviewedAt(), false,
                        "Plan #" + plan.getId() + " accepted",
                        "You accepted this plan with " + counts + ".", plan));
            }
            if ("SUPERSEDED".equals(status)) {
                ActionPlan next = replacement(plan, plans);
                LocalDateTime at = next == null ? plan.getReviewedAt() : next.getReviewedAt();
                inferred = true;
                events.add(planEvent(PLAN_SUPERSEDED, at, true,
                        "Plan #" + plan.getId() + " superseded",
                        next == null
                                ? "Replaced by a later accepted plan."
                                : "Replaced when plan #" + next.getId() + " was accepted.",
                        plan));
            }
        }
        return inferred;
    }

    // The first plan accepted after this one (it may since be superseded too).
    static ActionPlan replacement(ActionPlan plan, List<ActionPlan> plans) {
        ActionPlan best = null;
        for (ActionPlan other : plans) {
            String s = other.getStatus() == null ? "" : other.getStatus().toUpperCase(Locale.ROOT);
            if (other == plan || other.getReviewedAt() == null
                    || (!"ACCEPTED".equals(s) && !"SUPERSEDED".equals(s))
                    || !other.getReviewedAt().isAfter(plan.getReviewedAt())) {
                continue;
            }
            if (best == null || other.getReviewedAt().isBefore(best.getReviewedAt())) {
                best = other;
            }
        }
        return best;
    }

    private void actionEvents(Long farmId, List<HistoryEvent> events) {
        for (ActionExecution e : executionRepository.findByActionPlanItemActionPlanFarmId(farmId)) {
            ActionPlanItem item = e.getActionPlanItem();
            boolean done = ActionExecution.DONE.equals(e.getStatus());
            String name = item == null || item.getAgriculturalAction() == null
                    ? "Action" : item.getAgriculturalAction().getActionName();
            String category = item == null || item.getAgriculturalAction() == null
                    ? null : item.getAgriculturalAction().getCategory();
            Long planId = item == null || item.getActionPlan() == null ? null : item.getActionPlan().getId();
            events.add(new HistoryEvent(
                    done ? ACTION_DONE : ACTION_SKIPPED,
                    GROUP_ACTION,
                    actionTime(e),
                    false,
                    (done ? "Done: " : "Skipped: ") + name,
                    "Reported as " + (done ? "done" : "skipped") + " for " + e.getExecutedOn()
                            + (planId == null ? "" : " (plan #" + planId + ")") + ".",
                    planId, null, item == null ? null : item.getId(), name, category,
                    e.getExecutedOn(), e.getNote(), List.of()));
        }
    }

    // The reported date, at the time it was reported when that was the same day.
    static LocalDateTime actionTime(ActionExecution e) {
        if (e.getExecutedOn() == null) {
            return e.getRecordedAt();
        }
        if (e.getRecordedAt() != null && e.getRecordedAt().toLocalDate().equals(e.getExecutedOn())) {
            return e.getRecordedAt();
        }
        return e.getExecutedOn().atStartOfDay();
    }

    private static LocalDate eventDate(HistoryEvent e) {
        return e.executedOn() != null ? e.executedOn() : e.at().toLocalDate();
    }

    private static HistoryEvent planEvent(String type, LocalDateTime at, boolean inferred,
                                          String title, String detail, ActionPlan plan) {
        return new HistoryEvent(type, GROUP_PLAN, at, inferred, title, detail, plan.getId(),
                null, null, null, null, null, null, List.of());
    }

    private static String decisionCounts(ActionPlan plan) {
        if (plan.getItems() == null || plan.getItems().isEmpty()) {
            return "";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ActionPlanItem item : plan.getItems()) {
            String d = ActionPlanServiceImpl.decisionOf(item.getValidationMessage());
            counts.merge(d == null ? "UNRANKED" : d, 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " " + k));
        return " (" + String.join(", ", parts) + ")";
    }

    private static String describe(FarmStateReading r) {
        List<String> parts = new ArrayList<>();
        if (r.getCrop() != null) {
            parts.add("crop " + r.getCrop());
        }
        if (r.getGrowthStage() != null) {
            parts.add("stage " + r.getGrowthStage());
        }
        if (r.getSoilMoisture() != null) {
            parts.add("soil moisture " + r.getSoilMoisture());
        }
        if (r.getWaterAvailable() != null) {
            parts.add("water " + r.getWaterAvailable());
        }
        return parts.isEmpty() ? "no values" : String.join(", ", parts);
    }
}
