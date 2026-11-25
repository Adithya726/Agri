package com.agrisense.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.dto.ActionPlanItemResponse;
import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.AssistantResponse;
import com.agrisense.backend.dto.MonitoringResponse;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.MonitoredItem;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;

/*
 * Farm assistant, rule-based. The question is matched to an intent by
 * keywords (English, with a few Hindi and Telugu words); the answer is
 * assembled from the existing services and records. It adds no
 * agronomic advice of its own: when the data does not cover a
 * question it says so.
 */
@Service
public class FarmAssistantServiceImpl implements FarmAssistantService {

    public static final String NEXT_ACTION = "NEXT_ACTION";
    public static final String WHY_REJECTED = "WHY_REJECTED";
    public static final String PLAN_PROGRESS = "PLAN_PROGRESS";
    public static final String RISKS = "RISKS";
    public static final String WHAT_CHANGED = "WHAT_CHANGED";
    public static final String CURRENT_STATE = "CURRENT_STATE";
    public static final String UNKNOWN = "UNKNOWN";

    static final int MAX_QUESTION = 500;

    static final List<String> SUGGESTIONS = List.of(
            "What should I do next?",
            "Why was an action rejected?",
            "How is my plan progressing?",
            "What are the current risks?",
            "What changed in my farm state?",
            "What is my current soil moisture?");

    // Order breaks ties: the more specific intent first.
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put(WHY_REJECTED, List.of("reject", "rejected", "why not", "infeasible",
                "not recommended", "deferred", "needs data", "not allowed", "cannot do",
                "अस्वीकार", "क्यों नहीं", "తిరస్కర", "ఎందుకు కాదు"));
        KEYWORDS.put(WHAT_CHANGED, List.of("change", "changed", "changes", "difference",
                "different", "since last", "updated", "बदला", "बदलाव", "మార్పు", "మారింది"));
        KEYWORDS.put(NEXT_ACTION, List.of("next", "what should i do", "what to do", "what do i do",
                "to do", "todo", "अगला", "आगे", "क्या करूं", "क्या करना",
                "తర్వాత", "ఏమి చేయాలి", "ఏం చేయాలి"));
        KEYWORDS.put(PLAN_PROGRESS, List.of("progress", "how far", "pending", "completed",
                "complete", "finished", "done so far", "skipped", "how many", "प्रगति", "बाकी",
                "పురోగతి", "పూర్తి"));
        KEYWORDS.put(RISKS, List.of("risk", "risks", "danger", "threat", "warning", "alert",
                "problem", "problems", "जोखिम", "खतरा", "ప్రమాద", "సమస్య"));
        KEYWORDS.put(CURRENT_STATE, List.of("current state", "farm state", "moisture", "ph",
                "nitrogen", "phosphorus", "potassium", "water", "budget", "labour", "labor",
                "temperature", "humidity", "rainfall", "stage", "crop health", "disease",
                "values", "नमी", "पानी", "बजट", "తేమ", "నీరు", "బడ్జెట్"));
    }

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final FarmStateReadingRepository readingRepository;
    private final MonitoringService monitoringService;
    private final ActionPlanService actionPlanService;
    private final RiskAnalysisService riskAnalysisService;

    public FarmAssistantServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            FarmStateReadingRepository readingRepository,
            MonitoringService monitoringService,
            ActionPlanService actionPlanService,
            RiskAnalysisService riskAnalysisService) {
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.readingRepository = readingRepository;
        this.monitoringService = monitoringService;
        this.actionPlanService = actionPlanService;
        this.riskAnalysisService = riskAnalysisService;
    }

    @Override
    @Transactional(readOnly = true)
    public AssistantResponse ask(Long farmId, String question) {

        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            throw new InvalidRequestException("Ask a question about your farm.",
                    Map.of("question", "Question is required."));
        }
        if (q.length() > MAX_QUESTION) {
            throw new InvalidRequestException("Question must be at most " + MAX_QUESTION + " characters.",
                    Map.of("question", "Question must be at most " + MAX_QUESTION + " characters."));
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        String intent = detectIntent(q);

        Answer a = switch (intent) {
            case NEXT_ACTION -> nextAction(farmId);
            case WHY_REJECTED -> whyRejected(farmId, q);
            case PLAN_PROGRESS -> progress(farmId);
            case RISKS -> risks(farmId);
            case WHAT_CHANGED -> whatChanged(farmId);
            case CURRENT_STATE -> currentState(farm, q);
            default -> new Answer(false,
                    "I can only answer from this farm's records: its farm state, risk analysis,"
                            + " action plans and the actions you reported. Your question is not covered"
                            + " by that data, so I can't answer it without guessing. Try one of the"
                            + " suggested questions.",
                    List.of(), List.of());
        };

        return new AssistantResponse(farm.getId(), q, intent, a.answered, a.text,
                a.facts, a.sources, SUGGESTIONS);
    }

    // ------------------------------------------------------------------
    // Intent matching
    // ------------------------------------------------------------------

    static String detectIntent(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        String best = UNKNOWN;
        int bestScore = 0;
        for (Map.Entry<String, List<String>> e : KEYWORDS.entrySet()) {
            int score = 0;
            for (String k : e.getValue()) {
                if (matches(q, k)) {
                    score++;
                }
            }
            if (score > bestScore) {
                best = e.getKey();
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean matches(String q, String keyword) {
        if (keyword.chars().allMatch(c -> c < 128)) {
            // Word start, so "progress" also matches "progressing"; short
            // keywords such as "ph" must be whole words.
            String end = keyword.length() < 4 ? "\\b" : "";
            return Pattern.compile("\\b" + Pattern.quote(keyword) + end).matcher(q).find();
        }
        return q.contains(keyword);
    }

    // ------------------------------------------------------------------
    // Answers
    // ------------------------------------------------------------------

    private record Answer(boolean answered, String text, List<String> facts, List<String> sources) {}

    private Answer nextAction(Long farmId) {
        MonitoringResponse m = monitoringService.getFarmMonitoring(farmId);
        if (m.plan() == null) {
            ActionPlanResponse draft = latestWithStatus(farmId, "DRAFT");
            if (draft == null) {
                return new Answer(true,
                        "There is no accepted action plan for this farm yet, so there is no next action"
                                + " on record. Generate a plan on the Action Plan page and accept it.",
                        List.of(), List.of("Action plans"));
            }
            List<ActionPlanItemResponse> rec = byDecision(draft, ActionPrioritizer.RECOMMENDED);
            List<String> facts = new ArrayList<>();
            rec.forEach(i -> facts.add(i.getSequenceOrder() + ". " + i.getActionName()));
            return new Answer(true,
                    "No plan has been accepted yet. Draft plan #" + draft.getId() + " is waiting for"
                            + " your review" + (rec.isEmpty()
                            ? " and has no RECOMMENDED action."
                            : "; its first recommended action is \"" + rec.get(0).getActionName() + "\".")
                            + " Accept it on the Action Plan page to start tracking it.",
                    facts, List.of("Action plan #" + draft.getId()));
        }

        List<MonitoredItem> pending = m.items().stream()
                .filter(i -> i.execution() == null)
                .toList();
        String source = "Accepted plan #" + m.plan().id();
        if (pending.isEmpty()) {
            return new Answer(true,
                    "Every action in accepted plan #" + m.plan().id() + " has been reported ("
                            + m.progress().done() + " done, " + m.progress().skipped() + " skipped)."
                            + " If the farm has changed, check Replan on the Monitoring page.",
                    List.of(), List.of(source));
        }

        MonitoredItem next = pending.stream()
                .filter(i -> ActionPrioritizer.RECOMMENDED.equals(i.decision()))
                .findFirst()
                .orElse(pending.get(0));

        ActionPlanItemResponse detail = itemDetail(m.plan().id(), next.itemId());
        List<String> facts = new ArrayList<>();
        facts.add("Step " + next.sequenceOrder() + ": " + next.actionName()
                + (next.category() == null ? "" : " (" + next.category() + ")"));
        if (next.decision() != null) {
            facts.add("Decision when planned: " + next.decision());
        }
        if (detail != null && detail.getValidationMessage() != null) {
            facts.add(detail.getValidationMessage());
        }
        if (detail != null && detail.getSourceReference() != null) {
            facts.add("Source: " + detail.getSourceReference());
        }

        String text = "Your next pending action is \"" + next.actionName() + "\" (step "
                + next.sequenceOrder() + " of accepted plan #" + m.plan().id() + ").";
        if (!ActionPrioritizer.RECOMMENDED.equals(next.decision()) && next.decision() != null) {
            text += " It was planned as " + next.decision() + ", not RECOMMENDED, so check its reasons"
                    + " before doing it.";
        }
        text += " " + pending.size() + " action(s) are still pending.";
        return new Answer(true, text, facts, List.of(source));
    }

    private Answer whyRejected(Long farmId, String question) {
        List<ActionPlanResponse> plans = actionPlanService.getPlansByFarmId(farmId);
        if (plans.isEmpty()) {
            return new Answer(true, "This farm has no action plans yet, so nothing has been rejected.",
                    List.of(), List.of("Action plans"));
        }
        ActionPlanResponse plan = plans.stream()
                .filter(p -> "ACCEPTED".equalsIgnoreCase(p.getStatus()))
                .findFirst()
                .orElse(plans.get(0));

        String q = question.toLowerCase(Locale.ROOT);
        List<ActionPlanItemResponse> items = plan.getItems() == null ? List.of() : plan.getItems();
        List<ActionPlanItemResponse> named = items.stream()
                .filter(i -> i.getActionName() != null && mentions(q, i.getActionName()))
                .toList();

        List<String> facts = new ArrayList<>();
        List<String> sources = new ArrayList<>(List.of("Action plan #" + plan.getId()));

        for (ActionPlanResponse p : plans) {
            if ("REJECTED".equalsIgnoreCase(p.getStatus())) {
                facts.add("Plan #" + p.getId() + " was rejected by you"
                        + (p.getReviewedAt() == null ? "" : " on " + p.getReviewedAt().toLocalDate())
                        + ". No reason is stored for a plan you reject.");
            }
        }

        List<ActionPlanItemResponse> target = !named.isEmpty() ? named
                : byDecision(plan, ActionPrioritizer.REJECTED);

        if (target.isEmpty()) {
            List<ActionPlanItemResponse> other = new ArrayList<>(byDecision(plan, ActionPrioritizer.DEFERRED));
            other.addAll(byDecision(plan, ActionPrioritizer.NEEDS_DATA));
            other.stream().limit(5).forEach(i -> facts.add(i.getActionName() + ": " + i.getValidationMessage()));
            String text = "No action in plan #" + plan.getId() + " (" + plan.getStatus().toLowerCase(Locale.ROOT)
                    + ") was rejected by the checks.";
            if (!other.isEmpty()) {
                text += " " + other.size() + " action(s) were not recommended because they were deferred"
                        + " or need data; their reasons are listed.";
            }
            return new Answer(true, text, facts, sources);
        }

        target.stream().limit(5).forEach(i ->
                facts.add(i.getActionName() + " — " + (i.getValidationMessage() == null
                        ? "no reason stored" : i.getValidationMessage())));
        String text = named.isEmpty()
                ? target.size() + " action(s) in plan #" + plan.getId() + " were rejected. The reasons"
                        + " recorded by constraint validation are below."
                : "Here is what plan #" + plan.getId() + " recorded for the action you named.";
        return new Answer(true, text, facts, sources);
    }

    private Answer progress(Long farmId) {
        MonitoringResponse m = monitoringService.getFarmMonitoring(farmId);
        if (m.plan() == null) {
            return new Answer(true, "There is no accepted plan, so there is no progress to report yet.",
                    List.of(), List.of("Monitoring"));
        }
        MonitoringResponse.Progress p = m.progress();
        int pct = p.total() == 0 ? 0 : Math.round((p.done() + p.skipped()) * 100f / p.total());
        List<String> facts = new ArrayList<>();
        facts.add("Done: " + p.done());
        facts.add("Skipped: " + p.skipped());
        facts.add("Pending: " + p.pending());
        for (MonitoredItem i : m.items()) {
            if (i.execution() != null) {
                facts.add(i.actionName() + ": " + i.execution().status() + " on " + i.execution().executedOn()
                        + (i.execution().note() == null ? "" : " — \"" + i.execution().note() + "\""));
            }
        }
        return new Answer(true,
                "Accepted plan #" + m.plan().id() + ": " + (p.done() + p.skipped()) + " of " + p.total()
                        + " actions reported (" + pct + "%). " + m.message(),
                facts, List.of("Accepted plan #" + m.plan().id()));
    }

    private Answer risks(Long farmId) {
        RiskAnalysisResponse r;
        try {
            r = riskAnalysisService.analyzeFarmRisks(farmId);
        } catch (ResourceNotFoundException e) {
            return new Answer(false, "Risk analysis needs a saved farm state. " + e.getMessage() + ".",
                    List.of(), List.of());
        }
        List<String> facts = new ArrayList<>();
        List<RiskFinding> list = r.risks() == null ? List.of() : r.risks();
        list.stream()
                .sorted(Comparator.comparingInt(f -> severityRank(f.severity())))
                .forEach(f -> facts.add(f.severity() + " " + f.category() + " — " + f.parameter()
                        + (f.value() == null ? "" : " " + f.value()) + ": " + f.evidence()));
        return new Answer(true, "Overall risk is " + r.overallStatus() + ". " + r.message(),
                facts, List.of("Risk analysis of the saved farm state"));
    }

    private Answer whatChanged(Long farmId) {
        List<FarmStateReading> readings = readingRepository.findByFarmIdOrderByRecordedAtDesc(farmId);
        if (readings.size() < 2) {
            return new Answer(true, readings.isEmpty()
                    ? "No farm state has been recorded yet, so there is nothing to compare."
                    : "Only one farm state has been recorded, so there is nothing to compare yet.",
                    List.of(), List.of("Farm state readings"));
        }
        FarmStateReading latest = readings.get(0);
        FarmStateReading previous = readings.get(1);
        List<FieldChange> changes = MonitoringServiceImpl.compare(previous, latest);
        List<String> facts = new ArrayList<>();
        changes.forEach(c -> facts.add(c.field() + ": " + (c.before() == null ? "—" : c.before()) + " → "
                + (c.after() == null ? "—" : c.after())));
        String text = changes.isEmpty()
                ? "The latest farm state (" + latest.getRecordedAt().toLocalDate() + ") has the same values as"
                        + " the one before it (" + previous.getRecordedAt().toLocalDate() + ")."
                : changes.size() + " value(s) changed between " + previous.getRecordedAt().toLocalDate()
                        + " and " + latest.getRecordedAt().toLocalDate() + ".";
        return new Answer(true, text, facts, List.of("Farm state readings"));
    }

    private Answer currentState(Farm farm, String question) {
        FarmState s = farmStateRepository.findByFarm(farm).orElse(null);
        if (s == null) {
            return new Answer(false, "No farm state has been saved for this farm yet.", List.of(), List.of());
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("crop", s.getCrop());
        values.put("growth stage", s.getGrowthStage());
        values.put("soil moisture", s.getSoilMoisture());
        values.put("soil pH", s.getSoilPh());
        values.put("nitrogen", s.getNitrogen());
        values.put("phosphorus", s.getPhosphorus());
        values.put("potassium", s.getPotassium());
        values.put("temperature", s.getTemperature());
        values.put("humidity", s.getHumidity());
        values.put("rainfall", s.getRainfall());
        values.put("water available", s.getWaterAvailable());
        values.put("labour available", s.getLabourAvailable());
        values.put("budget available", s.getBudgetAvailable());
        values.put("crop health", s.getCropHealth());
        values.put("disease status", s.getDiseaseStatus());

        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("growth stage", List.of("stage"));
        aliases.put("soil moisture", List.of("moisture", "नमी", "తేమ"));
        aliases.put("soil pH", List.of("ph"));
        aliases.put("nitrogen", List.of("nitrogen"));
        aliases.put("phosphorus", List.of("phosphorus"));
        aliases.put("potassium", List.of("potassium"));
        aliases.put("temperature", List.of("temperature"));
        aliases.put("humidity", List.of("humidity"));
        aliases.put("rainfall", List.of("rainfall"));
        aliases.put("water available", List.of("water", "पानी", "నీరు"));
        aliases.put("labour available", List.of("labour", "labor"));
        aliases.put("budget available", List.of("budget", "बजट", "బడ్జెట్"));
        aliases.put("crop health", List.of("crop health"));
        aliases.put("disease status", List.of("disease"));

        String q = question.toLowerCase(Locale.ROOT);
        List<String> asked = new ArrayList<>();
        aliases.forEach((field, words) -> {
            if (words.stream().anyMatch(w -> matches(q, w))) {
                asked.add(field);
            }
        });

        Function<String, String> fmt = f -> f + ": " + (values.get(f) == null ? "not recorded" : values.get(f));
        List<String> facts = new ArrayList<>();
        (asked.isEmpty() ? new ArrayList<>(values.keySet()) : asked).forEach(f -> facts.add(fmt.apply(f)));

        FarmStateReading latest = readingRepository.findFirstByFarmIdOrderByRecordedAtDesc(farm.getId()).orElse(null);
        String when = latest == null ? "" : " (last saved " + latest.getRecordedAt().toLocalDate() + ")";
        String text = asked.isEmpty()
                ? "Here is the saved farm state" + when + "."
                : "From the saved farm state" + when + ": " + String.join("; ", facts) + ".";
        return new Answer(true, text, facts, List.of("Saved farm state"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private ActionPlanResponse latestWithStatus(Long farmId, String status) {
        return actionPlanService.getPlansByFarmId(farmId).stream()
                .filter(p -> status.equalsIgnoreCase(p.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private ActionPlanItemResponse itemDetail(Long planId, Long itemId) {
        try {
            ActionPlanResponse plan = actionPlanService.getPlanById(planId);
            return plan.getItems().stream()
                    .filter(i -> itemId.equals(i.getId()))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<ActionPlanItemResponse> byDecision(ActionPlanResponse plan, String decision) {
        if (plan.getItems() == null) {
            return List.of();
        }
        return plan.getItems().stream()
                .filter(i -> decision.equals(i.getDecision()))
                .sorted(Comparator.comparing(ActionPlanItemResponse::getSequenceOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    // True when the question contains a distinctive word (5+ letters) of the action name.
    static boolean mentions(String question, String actionName) {
        for (String w : actionName.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (w.length() >= 5 && question.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static int severityRank(String s) {
        if ("HIGH".equalsIgnoreCase(s)) {
            return 0;
        }
        if ("MEDIUM".equalsIgnoreCase(s)) {
            return 1;
        }
        return 2;
    }
}
