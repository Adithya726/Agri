package com.agrisense.backend.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ActionExecutionRequest;
import com.agrisense.backend.dto.MonitoringResponse;
import com.agrisense.backend.dto.MonitoringResponse.ChangeSince;
import com.agrisense.backend.dto.MonitoringResponse.Execution;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.MonitoredItem;
import com.agrisense.backend.dto.MonitoringResponse.PlanSummary;
import com.agrisense.backend.dto.MonitoringResponse.Progress;
import com.agrisense.backend.dto.MonitoringResponse.Reading;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanItemRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.security.OwnershipGuard;

@Service
public class MonitoringServiceImpl implements MonitoringService {

    static final int READINGS_SHOWN = 20;
    static final int NOTE_MAX = 500;

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final FarmStateReadingRepository readingRepository;
    private final ActionPlanRepository actionPlanRepository;
    private final ActionPlanItemRepository itemRepository;
    private final ActionExecutionRepository executionRepository;
    private final OwnershipGuard ownershipGuard;

    // Replaced in tests.
    Clock clock = Clock.systemDefaultZone();

    public MonitoringServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            FarmStateReadingRepository readingRepository,
            ActionPlanRepository actionPlanRepository,
            ActionPlanItemRepository itemRepository,
            ActionExecutionRepository executionRepository,
            OwnershipGuard ownershipGuard) {
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.readingRepository = readingRepository;
        this.actionPlanRepository = actionPlanRepository;
        this.itemRepository = itemRepository;
        this.executionRepository = executionRepository;
        this.ownershipGuard = ownershipGuard;
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public MonitoringResponse getFarmMonitoring(Long farmId) {

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        FarmState state = farmStateRepository.findByFarm(farm).orElse(null);

        List<FarmStateReading> readings =
                readingRepository.findByFarmIdOrderByRecordedAtDesc(farmId);

        List<Reading> shownReadings = readings.stream()
                .limit(READINGS_SHOWN)
                .map(MonitoringServiceImpl::toReading)
                .toList();

        ActionPlan plan = actionPlanRepository.findByFarmIdOrderByCreatedAtDesc(farmId)
                .stream()
                .filter(p -> "ACCEPTED".equalsIgnoreCase(p.getStatus()))
                .findFirst()
                .orElse(null);

        String crop = state != null ? state.getCrop() : farm.getCrop();
        String stage = state != null ? state.getGrowthStage() : farm.getGrowthStage();

        if (plan == null) {
            return new MonitoringResponse(
                    farm.getId(), farm.getFarmName(), crop, stage,
                    null, new Progress(0, 0, 0, 0), List.of(), shownReadings,
                    "No accepted action plan yet. Accept a plan on the Action Plan page to start monitoring it.");
        }

        Map<Long, ActionExecution> executions = new LinkedHashMap<>();
        for (ActionExecution e : executionRepository.findByActionPlanItemActionPlanId(plan.getId())) {
            executions.put(e.getActionPlanItem().getId(), e);
        }

        FarmStateReading latest = readings.isEmpty() ? null : readings.get(0);

        List<MonitoredItem> items = new ArrayList<>();
        int done = 0;
        int skipped = 0;

        List<ActionPlanItem> sorted = new ArrayList<>(plan.getItems());
        sorted.sort(Comparator.comparing(ActionPlanItem::getSequenceOrder,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (ActionPlanItem item : sorted) {
            ActionExecution e = executions.get(item.getId());
            ChangeSince change = null;

            if (e != null && ActionExecution.DONE.equals(e.getStatus())) {
                done++;
                change = changeSince(farmId, e, latest);
            } else if (e != null && ActionExecution.SKIPPED.equals(e.getStatus())) {
                skipped++;
            }

            items.add(new MonitoredItem(
                    item.getId(),
                    item.getSequenceOrder(),
                    item.getAgriculturalAction().getActionName(),
                    item.getAgriculturalAction().getCategory(),
                    ActionPlanServiceImpl.decisionOf(item.getValidationMessage()),
                    e == null ? null : new Execution(
                            e.getStatus(), e.getExecutedOn(), e.getNote(), e.getRecordedAt()),
                    change));
        }

        int total = items.size();
        Progress progress = new Progress(total, done, skipped, total - done - skipped);

        String message = progress.pending() == 0
                ? "Every action in the accepted plan has been reported."
                : progress.pending() + " of " + total + " actions are still pending.";

        return new MonitoringResponse(
                farm.getId(), farm.getFarmName(), crop, stage,
                new PlanSummary(plan.getId(), plan.getStatus(), plan.getCreatedAt(), plan.getReviewedAt()),
                progress, items, shownReadings, message);
    }

    /*
     * Baseline: the last reading taken before the action, meaning before
     * the end of the reported date and before the farmer reported it.
     * Compared with the newest reading.
     */
    ChangeSince changeSince(Long farmId, ActionExecution e, FarmStateReading latest) {

        LocalDateTime endOfDay = e.getExecutedOn().plusDays(1).atStartOfDay();
        LocalDateTime cutoff = e.getRecordedAt() != null && e.getRecordedAt().isBefore(endOfDay)
                ? e.getRecordedAt()
                : endOfDay;

        FarmStateReading baseline = readingRepository
                .findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(farmId, cutoff)
                .orElse(null);

        if (baseline == null) {
            return new ChangeSince(null, latest == null ? null : latest.getRecordedAt(), List.of(),
                    "No farm state was recorded before this action, so there is nothing to compare against.");
        }

        if (latest == null || Objects.equals(latest.getId(), baseline.getId())
                || !latest.getRecordedAt().isAfter(baseline.getRecordedAt())) {
            return new ChangeSince(baseline.getRecordedAt(), null, List.of(),
                    "The farm state has not been updated since this action. Update it to see what changed.");
        }

        List<FieldChange> changes = compare(baseline, latest);

        return new ChangeSince(
                baseline.getRecordedAt(),
                latest.getRecordedAt(),
                changes,
                changes.isEmpty()
                        ? "No recorded value has changed since this action."
                        : changes.size() + " recorded value(s) changed since this action.");
    }

    public static List<FieldChange> compare(FarmStateReading before, FarmStateReading after) {
        List<FieldChange> out = new ArrayList<>();
        text(out, "crop", before, after, FarmStateReading::getCrop);
        text(out, "growthStage", before, after, FarmStateReading::getGrowthStage);
        number(out, "soilMoisture", before, after, FarmStateReading::getSoilMoisture);
        number(out, "soilPh", before, after, FarmStateReading::getSoilPh);
        number(out, "nitrogen", before, after, FarmStateReading::getNitrogen);
        number(out, "phosphorus", before, after, FarmStateReading::getPhosphorus);
        number(out, "potassium", before, after, FarmStateReading::getPotassium);
        number(out, "temperature", before, after, FarmStateReading::getTemperature);
        number(out, "humidity", before, after, FarmStateReading::getHumidity);
        number(out, "rainfall", before, after, FarmStateReading::getRainfall);
        number(out, "waterAvailable", before, after, FarmStateReading::getWaterAvailable);
        number(out, "labourAvailable", before, after,
                r -> r.getLabourAvailable() == null ? null : r.getLabourAvailable().doubleValue());
        number(out, "budgetAvailable", before, after, FarmStateReading::getBudgetAvailable);
        text(out, "cropHealth", before, after, FarmStateReading::getCropHealth);
        text(out, "diseaseStatus", before, after, FarmStateReading::getDiseaseStatus);
        return out;
    }

    private static void number(List<FieldChange> out, String field,
            FarmStateReading before, FarmStateReading after,
            Function<FarmStateReading, Double> get) {
        Double b = get.apply(before);
        Double a = get.apply(after);
        if (Objects.equals(b, a)) {
            return;
        }
        Double diff = (a == null || b == null) ? null : round(a - b);
        out.add(new FieldChange(field, format(b), format(a), diff));
    }

    private static void text(List<FieldChange> out, String field,
            FarmStateReading before, FarmStateReading after,
            Function<FarmStateReading, String> get) {
        String b = blankToNull(get.apply(before));
        String a = blankToNull(get.apply(after));
        if (b == null ? a == null : b.equalsIgnoreCase(a == null ? "" : a)) {
            return;
        }
        out.add(new FieldChange(field, b, a, null));
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String format(Double v) {
        if (v == null) {
            return null;
        }
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(round(v));
    }

    private static Reading toReading(FarmStateReading r) {
        return new Reading(r.getId(), r.getRecordedAt(), r.getCrop(), r.getGrowthStage(),
                r.getSoilMoisture(), r.getSoilPh(), r.getNitrogen(), r.getPhosphorus(),
                r.getPotassium(), r.getTemperature(), r.getHumidity(), r.getRainfall(),
                r.getWaterAvailable(), r.getLabourAvailable(), r.getBudgetAvailable(),
                r.getCropHealth(), r.getDiseaseStatus());
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public MonitoringResponse recordExecution(Long itemId, ActionExecutionRequest request) {

        ActionPlanItem item = requireItem(itemId);
        ActionPlan plan = ownershipGuard.requireActionPlan(item.getActionPlan().getId());

        if (!"ACCEPTED".equalsIgnoreCase(plan.getStatus())) {
            throw new InvalidRequestException(
                    "Only actions in an accepted plan can be reported.");
        }

        Map<String, String> errors = new LinkedHashMap<>();

        String status = request == null || request.getStatus() == null
                ? null
                : request.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!ActionExecution.DONE.equals(status) && !ActionExecution.SKIPPED.equals(status)) {
            errors.put("status", "Status must be DONE or SKIPPED.");
        }

        LocalDate executedOn = request == null ? null : request.getExecutedOn();
        LocalDate today = LocalDate.now(clock);
        if (executedOn == null) {
            errors.put("executedOn", "Date is required.");
        } else if (executedOn.isAfter(today)) {
            errors.put("executedOn", "Date cannot be in the future.");
        } else if (plan.getCreatedAt() != null
                && executedOn.isBefore(plan.getCreatedAt().toLocalDate())) {
            errors.put("executedOn", "Date cannot be before the plan was created ("
                    + plan.getCreatedAt().toLocalDate() + ").");
        }

        String note = request == null || request.getNote() == null ? null : request.getNote().trim();
        if (note != null && note.length() > NOTE_MAX) {
            errors.put("note", "Note must be at most " + NOTE_MAX + " characters.");
        }

        if (!errors.isEmpty()) {
            throw new InvalidRequestException("Invalid action report", errors);
        }

        ActionExecution e = executionRepository.findByActionPlanItemId(itemId)
                .orElseGet(ActionExecution::new);
        e.setActionPlanItem(item);
        e.setStatus(status);
        e.setExecutedOn(executedOn);
        e.setNote(note == null || note.isEmpty() ? null : note);
        e.setRecordedAt(LocalDateTime.now(clock));
        executionRepository.save(e);

        return getFarmMonitoring(plan.getFarm().getId());
    }

    @Override
    @Transactional
    public MonitoringResponse clearExecution(Long itemId) {

        ActionPlanItem item = requireItem(itemId);
        ActionPlan plan = ownershipGuard.requireActionPlan(item.getActionPlan().getId());

        if (!"ACCEPTED".equalsIgnoreCase(plan.getStatus())) {
            throw new InvalidRequestException(
                    "Only actions in an accepted plan can be reported.");
        }

        ActionExecution e = executionRepository.findByActionPlanItemId(itemId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No report recorded for this action"));
        executionRepository.delete(e);

        return getFarmMonitoring(plan.getFarm().getId());
    }

    private ActionPlanItem requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan item not found"));
    }
}
