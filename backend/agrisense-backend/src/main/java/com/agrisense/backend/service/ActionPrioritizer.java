package com.agrisense.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.DecisionResponse.Dependency;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.DecisionResponse.ResourceSummary;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.FarmState;

/*
 * Prioritization, dependency analysis and sequencing of agricultural
 * actions that were already validated by ConstraintValidationService.
 *
 * It does not re-check constraints. It takes each action's existing
 * validation result and:
 *  1. decides: REJECTED (a constraint failed, or the action has no
 *     verified reference), NEEDS_DATA (a constraint could not be
 *     checked), or a feasible candidate;
 *  2. scores feasible candidates by the elevated risks (from
 *     RiskAnalysisService) that their category addresses;
 *  3. allocates the farm's water and budget to candidates in score
 *     order. Water and budget are treated as consumed by each action,
 *     so a candidate that is feasible alone but not together with the
 *     higher-ranked ones is DEFERRED with a RESOURCE dependency.
 *     Labour is a headcount reused from one action to the next, so it
 *     is checked per action (by the validator) and not summed;
 *  4. numbers RECOMMENDED actions 1..n as the execution sequence.
 *
 * The category-to-risk mapping only matches category labels to the
 * context categories (WATER, SOIL, DISEASE); it adds no agronomic
 * thresholds of its own.
 */
@Component
public class ActionPrioritizer {

    public static final String RECOMMENDED = "RECOMMENDED";
    public static final String DEFERRED = "DEFERRED";
    public static final String NEEDS_DATA = "NEEDS_DATA";
    public static final String REJECTED = "REJECTED";

    private static final String PLACEHOLDER_REFERENCE =
            "use the actual agricultural reference";

    public record Candidate(
            AgriculturalAction action,
            ConstraintValidationResponse validation
    ) {}

    public record Result(
            List<RankedAction> actions,
            ResourceSummary resources
    ) {}

    // Same evidence rule ActionPlanServiceImpl has always applied.
    public static boolean isReferenceSupported(AgriculturalAction action) {
        String source = action.getSourceReference();
        return "REFERENCE_SUPPORTED".equalsIgnoreCase(action.getEvidenceStatus())
                && source != null
                && !source.trim().isEmpty()
                && !source.toLowerCase(Locale.ROOT).contains(PLACEHOLDER_REFERENCE);
    }

    public Result prioritize(
            List<Candidate> candidates,
            RiskAnalysisResponse risk,
            FarmState farmState) {

        List<RiskFinding> elevated = elevatedRisks(risk);

        List<Working> feasible = new ArrayList<>();
        List<Working> needsData = new ArrayList<>();
        List<Working> rejected = new ArrayList<>();

        for (Candidate candidate : candidates) {

            Working w = new Working(candidate);
            AgriculturalAction action = candidate.action();
            String status = validationStatus(candidate.validation());

            w.addressed = addressedRisks(action.getCategory(), elevated);
            w.score = score(w.addressed);

            if (!isReferenceSupported(action)) {
                w.decision = REJECTED;
                w.reasons.add("Rejected: the action has no verified agronomic reference"
                        + " (evidence status: "
                        + (action.getEvidenceStatus() == null ? "not set" : action.getEvidenceStatus())
                        + ").");
                addFailures(w, "INFEASIBLE");
                rejected.add(w);
                continue;
            }

            if ("INFEASIBLE".equals(status)) {
                w.decision = REJECTED;
                addFailures(w, "INFEASIBLE");
                if (w.reasons.isEmpty()) {
                    w.reasons.add("Rejected: " + message(candidate.validation()));
                }
                rejected.add(w);
                continue;
            }

            if (!"FEASIBLE".equals(status)) {
                w.decision = NEEDS_DATA;
                for (ConstraintResult c : constraints(candidate.validation())) {
                    if ("NEEDS_INPUT".equalsIgnoreCase(c.getStatus())) {
                        w.reasons.add("Needs your " + label(c.getCategory()) + ": " + c.getMessage());
                        w.dependencies.add(new Dependency("DATA", null,
                                "Add your " + label(c.getCategory()) + " before this step can be scheduled. "
                                        + c.getMessage()));
                    }
                }
                if (w.reasons.isEmpty()) {
                    w.reasons.add("Needs more farm details: " + message(candidate.validation()));
                }
                needsData.add(w);
                continue;
            }

            feasible.add(w);
        }

        Comparator<Working> byPriority = Comparator
                .comparingInt((Working w) -> -w.score)
                .thenComparingDouble(w -> nullToZero(w.candidate.action().getRequiredBudget()))
                .thenComparingDouble(w -> nullToZero(w.candidate.action().getRequiredWater()))
                .thenComparing(w -> w.candidate.action().getId(),
                        Comparator.nullsLast(Comparator.naturalOrder()));

        feasible.sort(byPriority);
        needsData.sort(byPriority);
        rejected.sort(byPriority);

        // Allocate consumable resources in priority order.
        Double waterAvailable = farmState == null ? null : farmState.getWaterAvailable();
        Double budgetAvailable = farmState == null ? null : farmState.getBudgetAvailable();
        Integer labourAvailable = farmState == null ? null : farmState.getLabourAvailable();

        double waterUsed = 0;
        double budgetUsed = 0;
        int peakLabour = 0;
        List<Working> scheduled = new ArrayList<>();

        for (Working w : feasible) {

            AgriculturalAction action = w.candidate.action();
            double water = nullToZero(action.getRequiredWater());
            double budget = nullToZero(action.getRequiredBudget());

            List<String> shortfalls = new ArrayList<>();

            if (waterAvailable != null && waterUsed + water > waterAvailable) {
                shortfalls.add("water (needs " + fmt(water) + ", " + fmt(waterAvailable - waterUsed)
                        + " of " + fmt(waterAvailable) + " left after higher-ranked actions)");
            }
            if (budgetAvailable != null && budgetUsed + budget > budgetAvailable) {
                shortfalls.add("budget (needs " + fmt(budget) + ", " + fmt(budgetAvailable - budgetUsed)
                        + " of " + fmt(budgetAvailable) + " left after higher-ranked actions)");
            }

            if (!shortfalls.isEmpty()) {
                w.decision = DEFERRED;
                w.reasons.add("Feasible on its own, but deferred: not enough "
                        + String.join(" and ", shortfalls) + ".");
                for (Working earlier : scheduled) {
                    w.dependencies.add(new Dependency("RESOURCE",
                            earlier.candidate.action().getId(),
                            "Shares the farm's water/budget with higher-ranked action \""
                                    + earlier.candidate.action().getActionName()
                                    + "\"; can follow once more resources are available."));
                }
                continue;
            }

            waterUsed += water;
            budgetUsed += budget;
            peakLabour = Math.max(peakLabour,
                    action.getRequiredLabour() == null ? 0 : action.getRequiredLabour());

            w.decision = RECOMMENDED;
            w.reasons.add(passedSummary(candidate(w)));
            if (!scheduled.isEmpty()) {
                Working previous = scheduled.get(scheduled.size() - 1);
                w.dependencies.add(new Dependency("SEQUENCE",
                        previous.candidate.action().getId(),
                        "Scheduled after \"" + previous.candidate.action().getActionName()
                                + "\" so labour is not double-booked."));
            }
            scheduled.add(w);
        }

        for (Working w : feasible) {
            if (w.addressed.isEmpty()) {
                w.reasons.add("No elevated risk matches this action's category; ranked by resource use.");
            } else {
                w.reasons.add("Addresses " + String.join("; ", w.addressed) + ".");
            }
        }

        // Final ranking: recommended, deferred, needs data, rejected.
        List<Working> ordered = new ArrayList<>();
        ordered.addAll(scheduled);
        feasible.stream().filter(w -> DEFERRED.equals(w.decision)).forEach(ordered::add);
        ordered.addAll(needsData);
        ordered.addAll(rejected);

        List<RankedAction> ranked = new ArrayList<>();
        int rank = 1;
        int sequence = 1;
        for (Working w : ordered) {
            AgriculturalAction a = w.candidate.action();
            ranked.add(new RankedAction(
                    rank++,
                    RECOMMENDED.equals(w.decision) ? sequence++ : null,
                    a.getId(),
                    a.getActionName(),
                    a.getCategory(),
                    w.decision,
                    validationStatus(w.candidate.validation()),
                    w.score,
                    List.copyOf(w.addressed),
                    List.copyOf(w.reasons),
                    List.copyOf(w.dependencies),
                    constraints(w.candidate.validation()),
                    a.getRequiredWater(),
                    a.getRequiredLabour(),
                    a.getRequiredBudget(),
                    a.getEvidenceStatus(),
                    a.getSourceReference()));
        }

        ResourceSummary resources = new ResourceSummary(
                waterAvailable, labourAvailable, budgetAvailable,
                waterUsed, budgetUsed, peakLabour);

        return new Result(ranked, resources);
    }

    /*
     * Category label -> context category. Returns the context
     * categories an action category addresses, or none.
     */
    static List<String> riskCategoriesFor(String actionCategory) {
        if (actionCategory == null) {
            return List.of();
        }
        String c = actionCategory.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        if (c.contains("irrigat") || c.contains("water")) {
            result.add("WATER");
        }
        if (c.contains("fertili") || c.contains("nutrient") || c.contains("soil")) {
            result.add("SOIL");
        }
        if (c.contains("pest") || c.contains("disease")) {
            result.add("DISEASE");
        }
        return result;
    }

    private List<String> addressedRisks(String actionCategory, List<RiskFinding> elevated) {
        List<String> categories = riskCategoriesFor(actionCategory);
        List<String> result = new ArrayList<>();
        for (RiskFinding r : elevated) {
            if (r.category() != null && categories.contains(r.category().toUpperCase(Locale.ROOT))) {
                result.add(r.severity() + " " + r.category() + " risk (" + r.parameter() + ": " + r.evidence() + ")");
            }
        }
        return result;
    }

    private int score(List<String> addressed) {
        int score = 0;
        for (String a : addressed) {
            score += a.startsWith("HIGH") ? 30 : 20;
        }
        return score;
    }

    private List<RiskFinding> elevatedRisks(RiskAnalysisResponse risk) {
        if (risk == null || risk.risks() == null) {
            return List.of();
        }
        return risk.risks().stream()
                .filter(r -> "HIGH".equalsIgnoreCase(r.severity()) || "MEDIUM".equalsIgnoreCase(r.severity()))
                .toList();
    }

    private void addFailures(Working w, String status) {
        for (ConstraintResult c : constraints(w.candidate.validation())) {
            if (status.equalsIgnoreCase(c.getStatus())) {
                w.reasons.add("Rejected by " + c.getCategory() + ": " + c.getMessage());
            }
        }
    }

    private static ConstraintValidationResponse candidate(Working w) {
        return w.candidate.validation();
    }

    // Plain-language summary of the checks a recommended step passed.
    static String passedSummary(ConstraintValidationResponse v) {
        List<String> checked = new ArrayList<>();
        List<String> guide = new ArrayList<>();
        for (ConstraintResult c : constraints(v)) {
            if ("FEASIBLE".equalsIgnoreCase(c.getStatus())) {
                checked.add(label(c.getCategory()));
            } else if ("FIELD_GUIDE".equalsIgnoreCase(c.getStatus())) {
                guide.add(label(c.getCategory()));
            }
        }
        String text = checked.isEmpty()
                ? "No conflicts found."
                : "Fits your " + String.join(", ", checked) + ".";
        if (!guide.isEmpty()) {
            text += " The ANGRAU guide sets no separate " + String.join(", ", guide)
                    + " figure; use the amounts written in the step.";
        }
        return text;
    }

    static String label(String category) {
        if (category == null) {
            return "details";
        }
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "CROP_APPLICABILITY" -> "crop";
            case "GROWTH_STAGE_APPLICABILITY" -> "growth stage";
            case "WATER" -> "water";
            case "LABOUR" -> "labour";
            case "BUDGET" -> "budget";
            default -> category.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String validationStatus(ConstraintValidationResponse v) {
        return v == null || v.getOverallStatus() == null
                ? "NOT_CHECKED"
                : v.getOverallStatus().toUpperCase(Locale.ROOT);
    }

    private static List<ConstraintResult> constraints(ConstraintValidationResponse v) {
        return v == null || v.getConstraints() == null ? List.of() : List.copyOf(v.getConstraints());
    }

    private static String message(ConstraintValidationResponse v) {
        return v == null || v.getMessage() == null ? "no validation result" : v.getMessage();
    }

    private static double nullToZero(Number n) {
        return n == null ? 0 : n.doubleValue();
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static final class Working {
        final Candidate candidate;
        String decision;
        int score;
        List<String> addressed = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final List<Dependency> dependencies = new ArrayList<>();

        Working(Candidate candidate) {
            this.candidate = candidate;
        }
    }
}
