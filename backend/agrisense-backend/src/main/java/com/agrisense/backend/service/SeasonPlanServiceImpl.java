package com.agrisense.backend.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;
import com.agrisense.backend.dto.SeasonPlanResponse;
import com.agrisense.backend.dto.StepRequirements;
import com.agrisense.backend.dto.SeasonPlanResponse.Alert;
import com.agrisense.backend.dto.SeasonPlanResponse.Change;
import com.agrisense.backend.dto.SeasonPlanResponse.CropDays;
import com.agrisense.backend.dto.SeasonPlanResponse.Due;
import com.agrisense.backend.dto.SeasonPlanResponse.StagePlan;
import com.agrisense.backend.dto.SeasonPlanResponse.StageStep;
import com.agrisense.backend.dto.SeasonPlanResponse.Step;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;

/*
 * Season plan: one plan for the whole crop season, from the first
 * growth stage to harvest, that re-decides on every request from the
 * farm's latest state.
 *
 *  - stages: every sourced ANGRAU step for the crop, grouped by growth
 *    stage and marked DONE / NOW / UPCOMING against the current stage;
 *  - doNow / onHold: the current stage's steps, checked by
 *    ConstraintValidationService and ordered by ActionPrioritizer with
 *    today's risks, so a disruption (disease, water drop, budget cut)
 *    changes the order or puts a step on hold straight away;
 *  - alerts: elevated findings of the existing risk analysis, each with
 *    the stage steps that address it;
 *  - changes: what moved between the two most recent farm readings.
 *
 * It adds no thresholds or quantities of its own: every number shown is
 * either the farmer's own reading or comes from the ANGRAU source text.
 */
@Service
public class SeasonPlanServiceImpl implements SeasonPlanService {

    static final List<String> STAGES = List.of(
            "Seedling", "Vegetative", "Flowering", "Fruiting", "Maturity", "Harvest");

    static final String ON_TRACK = "ON_TRACK";
    static final String ACT_NOW = "ACT_NOW";
    static final String ADD_DETAILS = "ADD_DETAILS";
    static final String NO_GUIDE = "NO_GUIDE";

    private static final String LOCAL_HELP =
            "Show this to your local Rythu Bharosa Kendra (RBK) or agriculture officer for a field visit.";

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final FarmStateReadingRepository readingRepository;
    private final AgriculturalActionRepository actionRepository;
    private final ActionExecutionRepository executionRepository;
    private final ConstraintValidationService constraintValidationService;
    private final RiskAnalysisService riskAnalysisService;
    private final ActionPrioritizer actionPrioritizer;
    private final RequirementCatalog requirementCatalog;

    public SeasonPlanServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            FarmStateReadingRepository readingRepository,
            AgriculturalActionRepository actionRepository,
            ActionExecutionRepository executionRepository,
            ConstraintValidationService constraintValidationService,
            RiskAnalysisService riskAnalysisService,
            ActionPrioritizer actionPrioritizer,
            RequirementCatalog requirementCatalog) {
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.readingRepository = readingRepository;
        this.actionRepository = actionRepository;
        this.executionRepository = executionRepository;
        this.constraintValidationService = constraintValidationService;
        this.riskAnalysisService = riskAnalysisService;
        this.actionPrioritizer = actionPrioritizer;
        this.requirementCatalog = requirementCatalog;
    }

    @Override
    @Transactional(readOnly = true)
    public SeasonPlanResponse buildSeasonPlan(Long farmId) {

        if (farmId == null) {
            throw new InvalidRequestException("Farm ID is required.");
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        FarmState state = farmStateRepository.findByFarm(farm)
                .orElseThrow(() -> new InvalidRequestException(
                        "Add your farm's current state on the Farm State page first."));

        String crop = blankToNull(state.getCrop());
        if (crop == null) {
            throw new InvalidRequestException(
                    "Add your current crop on the Farm State page first.");
        }

        String stage = canonicalStage(state.getGrowthStage());
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        String season = seasonFor(today);
        CropDays cropDays = cropDays(state, today);
        List<String> notes = new ArrayList<>();

        // All sourced steps for this crop, across the season.
        List<AgriculturalAction> cropActions = actionRepository.findByActiveTrue().stream()
                .filter(a -> a.getApplicableCrop() != null
                        && a.getApplicableCrop().trim().equalsIgnoreCase(crop))
                .filter(ActionPrioritizer::isReferenceSupported)
                .toList();

        Set<Long> completed = completedActionIds(farmId);
        List<String> missing = missingDetails(state);
        List<Change> changes = new ArrayList<>();
        LocalDateTime changesSince = recentChanges(farmId, changes);

        if (cropActions.isEmpty()) {
            return new SeasonPlanResponse(farmId, farm.getFarmName(), farm.getLocation(), crop,
                    farm.getVariety(), stage, season, LocalDateTime.now(), NO_GUIDE,
                    "No ANGRAU steps are stored for " + crop + " yet, so no plan can be built."
                            + " Plans are available for rice and maize.",
                    List.of(), List.of(), List.of(), changes, changesSince, missing, List.of(),
                    List.of(LOCAL_HELP), cropDays);
        }

        // Whole-season timeline.
        int currentIndex = stage == null ? -1 : STAGES.indexOf(stage);
        List<StagePlan> stages = new ArrayList<>();
        for (int i = 0; i < STAGES.size(); i++) {
            String s = STAGES.get(i);
            List<StageStep> steps = cropActions.stream()
                    .filter(a -> s.equalsIgnoreCase(a.getApplicableGrowthStage().trim()))
                    .map(a -> {
                        StepRequirements req = requirements(a, farm, season);
                        return new StageStep(a.getId(), a.getActionName(), a.getCategory(),
                                a.getDescription(), completed.contains(a.getId()), a.getSourceReference(),
                                req, due(req, state, today));
                    })
                    .toList();
            String status = currentIndex < 0 ? "UPCOMING"
                    : i < currentIndex ? "DONE"
                    : i == currentIndex ? "NOW" : "UPCOMING";
            stages.add(new StagePlan(s, status, steps));
        }

        // Today's steps, re-decided on the current farm state.
        List<AgriculturalAction> nowActions = stage == null ? List.of() : cropActions.stream()
                .filter(a -> stage.equalsIgnoreCase(a.getApplicableGrowthStage().trim()))
                .filter(a -> !completed.contains(a.getId()))
                .toList();

        RiskAnalysisResponse risk = riskAnalysisService.analyzeFarmRisks(farmId);

        List<Step> doNow = new ArrayList<>();
        List<Step> onHold = new ArrayList<>();

        if (!nowActions.isEmpty()) {
            Map<Long, AgriculturalAction> byId = new HashMap<>();
            List<ActionPrioritizer.Candidate> candidates = new ArrayList<>();
            for (AgriculturalAction a : nowActions) {
                byId.put(a.getId(), a);
                ConstraintValidationResponse v =
                        constraintValidationService.validateAction(farmId, state, a);
                candidates.add(new ActionPrioritizer.Candidate(a, v));
            }

            for (RankedAction r : actionPrioritizer.prioritize(candidates, risk, state).actions()) {
                AgriculturalAction a = byId.get(r.actionId());
                StepRequirements req = requirements(a, farm, season);
                Step step = new Step(r.sequence(), a.getId(), a.getActionName(), a.getCategory(),
                        a.getDescription(), plainDecision(r.decision()), r.reasons(),
                        r.constraints(), a.getSourceReference(), req, due(req, state, today));
                if (ActionPrioritizer.RECOMMENDED.equals(r.decision())) {
                    doNow.add(step);
                } else {
                    onHold.add(step);
                }
            }
        } else if (stage == null) {
            notes.add("Add your crop's growth stage on the Farm State page to see today's steps.");
        } else {
            String next = nextStageWithSteps(stages, currentIndex);
            notes.add("All ANGRAU steps for " + crop + " at the " + stage + " stage are "
                    + (stageHasSteps(stages, currentIndex) ? "already done." : "not listed in the guide.")
                    + (next == null ? "" : " Your next steps start at the " + next + " stage."));
        }

        notes.addAll(dateNotes(crop, state, stages));

        List<Alert> alerts = alerts(risk, doNow, onHold);

        String status;
        String headline;
        if (!alerts.isEmpty()) {
            status = ACT_NOW;
            headline = alerts.size() == 1
                    ? "One problem needs attention now. Follow the \"Do now\" steps in order."
                    : alerts.size() + " problems need attention now. Follow the \"Do now\" steps in order.";
        } else if (!missing.isEmpty()) {
            status = ADD_DETAILS;
            headline = "Your plan is ready, but add the missing farm details so every step can be checked.";
        } else {
            status = ON_TRACK;
            headline = stage == null
                    ? "Your farm is on track."
                    : "Your farm is on track. Follow the " + stage + " stage steps below.";
        }

        if (!changes.isEmpty()) {
            headline += " Your latest readings changed, so this plan was updated for them.";
        }

        return new SeasonPlanResponse(farmId, farm.getFarmName(), farm.getLocation(), crop,
                farm.getVariety(), stage, season, LocalDateTime.now(), status, headline,
                doNow, onHold, alerts, changes, changesSince, missing, stages, notes, cropDays);
    }

    // ------------------------------------------------------------------
    // Dates: the guide's day counts turned into calendar dates for this
    // farm. Nothing here is estimated: a step with no day count in the
    // guide, or a farm with no date, says so instead.

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    static CropDays cropDays(FarmState state, LocalDate today) {
        LocalDate sown = state.getSowingDate();
        LocalDate planted = state.getTransplantingDate();
        return new CropDays(sown, sown == null ? null : (int) ChronoUnit.DAYS.between(sown, today),
                planted, planted == null ? null : (int) ChronoUnit.DAYS.between(planted, today));
    }

    static Due due(StepRequirements req, FarmState state, LocalDate today) {
        if (req == null || req.timing() == null) {
            return null;
        }
        StepRequirements.Timing t = req.timing();
        String text = t.text() == null ? "" : t.text().trim();
        String countFrom = t.countFrom() == null ? "" : t.countFrom().toUpperCase(Locale.ROOT);

        if (t.fromDay() == null) {
            return new Due("BY_STAGE", null, null, text);
        }

        LocalDate anchor;
        String word;
        switch (countFrom) {
            case "SOWING" -> { anchor = state.getSowingDate(); word = "sowing"; }
            case "TRANSPLANTING" -> { anchor = state.getTransplantingDate(); word = "transplanting"; }
            default -> {
                // EMERGENCE: the day seedlings come up is not stored.
                return new Due("BY_STAGE", null, null,
                        text + ". Count the days from when the seedlings came up.");
            }
        }

        if (anchor == null) {
            return new Due("ADD_DATE", null, null, text + ". Add your " + word
                    + " date on the Farm State page to see the exact date.");
        }

        LocalDate from = anchor.plusDays(t.fromDay());
        LocalDate to = t.toDay() == null ? null : anchor.plusDays(t.toDay());
        long day = ChronoUnit.DAYS.between(anchor, today);

        if (today.isBefore(from)) {
            long in = ChronoUnit.DAYS.between(today, from);
            return new Due("COMING", from, to, "Due from " + DAY.format(from)
                    + (in == 1 ? " (tomorrow)" : " (in " + in + " days)") + ". " + text + ".");
        }
        if (to != null && today.isAfter(to)) {
            return new Due("PASSED", from, to, "The time for this ended on " + DAY.format(to)
                    + ". " + text + ".");
        }
        if (to == null) {
            // The guide gives a start but no end (for example "until the
            // crop is established"), so it is not marked due now.
            return new Due("STARTED", from, null, "Started on " + DAY.format(from)
                    + "; today is day " + day + " after " + word + ". " + text + ".");
        }
        return new Due("DUE_NOW", from, to, "Due now: today is day " + day + " after " + word
                + ", and the time for this ends on " + DAY.format(to) + ". "
                + text + ".");
    }

    // Asks for missing dates, and points out steps of another stage that
    // are due by date, so the farmer can check the crop's stage.
    static List<String> dateNotes(String crop, FarmState state, List<StagePlan> stages) {
        List<String> out = new ArrayList<>();
        boolean rice = crop != null && crop.toLowerCase(Locale.ROOT).contains("rice");
        if (state.getSowingDate() == null && (!rice || state.getTransplantingDate() == null)) {
            out.add(rice
                    ? "Add your sowing and transplanting dates on the Farm State page so each step shows its due date."
                    : "Add your sowing date on the Farm State page so each step shows its due date.");
        } else if (rice && state.getTransplantingDate() == null) {
            out.add("Add your transplanting date on the Farm State page; most rice steps count from it.");
        }
        for (StagePlan p : stages) {
            // Only later stages: a step of an earlier stage that is still
            // open does not mean the crop has gone back.
            if (!"UPCOMING".equals(p.status())) {
                continue;
            }
            for (StageStep st : p.steps()) {
                if (!st.completed() && st.due() != null && "DUE_NOW".equals(st.due().status())) {
                    out.add("By date, \"" + st.name() + "\" (" + p.stage() + " stage) is due now."
                            + " If your crop has reached the " + p.stage()
                            + " stage, change Growth stage on the Farm State page.");
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------

    // What the step needs on this farm, or null for an action that has
    // no sourced requirements (for example one an admin added).
    private StepRequirements requirements(AgriculturalAction a, Farm farm, String season) {
        if (requirementCatalog == null) {
            return null;
        }
        return requirementCatalog.requirementsFor(a, farm.getArea(), season).orElse(null);
    }

    private List<Alert> alerts(RiskAnalysisResponse risk, List<Step> doNow, List<Step> onHold) {
        List<Alert> out = new ArrayList<>();
        if (risk == null || risk.risks() == null) {
            return out;
        }
        List<RiskFinding> elevated = new ArrayList<>(risk.risks().stream()
                .filter(r -> "HIGH".equalsIgnoreCase(r.severity()) || "MEDIUM".equalsIgnoreCase(r.severity()))
                .toList());
        // HIGH first.
        elevated.sort((a, b) -> Boolean.compare(
                !"HIGH".equalsIgnoreCase(a.severity()), !"HIGH".equalsIgnoreCase(b.severity())));

        for (RiskFinding r : elevated) {
            String category = r.category() == null ? "" : r.category().toUpperCase(Locale.ROOT);
            List<String> doThis = new ArrayList<>();
            for (Step s : doNow) {
                if (ActionPrioritizer.riskCategoriesFor(s.category()).contains(category)) {
                    doThis.add("Step " + s.order() + ": " + s.name());
                }
            }
            for (Step s : onHold) {
                if (ActionPrioritizer.riskCategoriesFor(s.category()).contains(category)) {
                    doThis.add(s.name() + " (on hold: " + s.decision().toLowerCase(Locale.ROOT) + ")");
                }
            }
            if (doThis.isEmpty()) {
                if ("RESOURCES".equals(category)) {
                    doThis.add("Steps that need more of this are put on hold; do the other steps first.");
                }
                doThis.add("The ANGRAU guide has no step for this at your current stage. " + LOCAL_HELP);
            }
            doThis.add("After acting, update your Farm State readings so the plan can recheck.");
            out.add(new Alert(r.severity().toUpperCase(Locale.ROOT), topic(category, r.parameter()),
                    r.parameter() + ": " + (r.value() == null ? "not recorded" : r.value()),
                    r.evidence(), doThis));
        }
        return out;
    }

    private LocalDateTime recentChanges(Long farmId, List<Change> out) {
        List<FarmStateReading> readings = readingRepository.findByFarmIdOrderByRecordedAtDesc(farmId);
        if (readings.size() < 2) {
            return null;
        }
        FarmStateReading latest = readings.get(0);
        FarmStateReading previous = readings.get(1);
        for (FieldChange c : MonitoringServiceImpl.compare(previous, latest)) {
            String label = label(c.field());
            String before = c.before() == null ? "not recorded" : c.before();
            String after = c.after() == null ? "not recorded" : c.after();
            String sentence = label + " changed from " + before + " to " + after
                    + (c.difference() == null ? "" : " (" + (c.difference() > 0 ? "+" : "")
                            + trim(c.difference()) + ")")
                    + ".";
            out.add(new Change(c.field(), label, before, after, sentence));
        }
        return previous.getRecordedAt();
    }

    private Set<Long> completedActionIds(Long farmId) {
        Set<Long> done = new LinkedHashSet<>();
        for (ActionExecution e : executionRepository.findByActionPlanItemActionPlanFarmId(farmId)) {
            if (ActionExecution.DONE.equals(e.getStatus())
                    && e.getActionPlanItem() != null
                    && e.getActionPlanItem().getAgriculturalAction() != null) {
                done.add(e.getActionPlanItem().getAgriculturalAction().getId());
            }
        }
        return done;
    }

    static List<String> missingDetails(FarmState s) {
        List<String> m = new ArrayList<>();
        if (blankToNull(s.getGrowthStage()) == null) m.add("Growth stage");
        if (s.getSoilMoisture() == null) m.add("Soil moisture");
        if (s.getSoilPh() == null) m.add("Soil pH");
        if (s.getNitrogen() == null) m.add("Nitrogen");
        if (s.getPhosphorus() == null) m.add("Phosphorus");
        if (s.getPotassium() == null) m.add("Potassium");
        if (s.getTemperature() == null) m.add("Temperature");
        if (s.getHumidity() == null) m.add("Humidity");
        if (s.getRainfall() == null) m.add("Rainfall");
        if (s.getWaterAvailable() == null) m.add("Water available");
        if (s.getLabourAvailable() == null) m.add("Labour available");
        if (s.getBudgetAvailable() == null) m.add("Budget available");
        if (blankToNull(s.getCropHealth()) == null) m.add("Crop health");
        if (blankToNull(s.getDiseaseStatus()) == null) m.add("Disease status");
        return m;
    }

    // Kharif Jun-Oct, Rabi Nov-Mar, Summer Apr-May (calendar convention
    // used by ANGRAU's Kharif / Rabi packages of practices).
    static String seasonFor(LocalDate date) {
        int m = date.getMonthValue();
        if (m >= 6 && m <= 10) {
            return "Kharif";
        }
        if (m >= 4 && m <= 5) {
            return "Summer";
        }
        return "Rabi";
    }

    static String canonicalStage(String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        for (String s : STAGES) {
            if (s.equalsIgnoreCase(v)) {
                return s;
            }
        }
        return v;
    }

    private static boolean stageHasSteps(List<StagePlan> stages, int index) {
        return index >= 0 && index < stages.size() && !stages.get(index).steps().isEmpty();
    }

    private static String nextStageWithSteps(List<StagePlan> stages, int index) {
        for (int i = index + 1; i < stages.size(); i++) {
            if (!stages.get(i).steps().isEmpty()) {
                return stages.get(i).stage();
            }
        }
        return null;
    }

    static String plainDecision(String decision) {
        if (decision == null) {
            return "Check";
        }
        return switch (decision) {
            case ActionPrioritizer.RECOMMENDED -> "Do now";
            case ActionPrioritizer.DEFERRED -> "Wait for resources";
            case ActionPrioritizer.NEEDS_DATA -> "Add details first";
            case ActionPrioritizer.REJECTED -> "Not possible now";
            default -> decision;
        };
    }

    private static String topic(String category, String parameter) {
        return switch (category) {
            case "WATER" -> "Water";
            case "SOIL" -> "Soil";
            case "WEATHER" -> "Weather";
            case "DISEASE" -> "Disease";
            case "RESOURCES" -> "Resources";
            case "CROP" -> "Crop";
            default -> parameter == null ? "Farm" : parameter;
        };
    }

    static String label(String field) {
        return switch (field) {
            case "crop" -> "Crop";
            case "growthStage" -> "Growth stage";
            case "soilMoisture" -> "Soil moisture";
            case "soilPh" -> "Soil pH";
            case "nitrogen" -> "Nitrogen";
            case "phosphorus" -> "Phosphorus";
            case "potassium" -> "Potassium";
            case "temperature" -> "Temperature";
            case "humidity" -> "Humidity";
            case "rainfall" -> "Rainfall";
            case "waterAvailable" -> "Water available";
            case "labourAvailable" -> "Labour available";
            case "budgetAvailable" -> "Budget available";
            case "cropHealth" -> "Crop health";
            case "diseaseStatus" -> "Disease status";
            default -> field;
        };
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
