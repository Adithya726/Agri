package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.FarmState;

class ActionPrioritizerTest {

    private final ActionPrioritizer prioritizer = new ActionPrioritizer();

    private static AgriculturalAction action(long id, String name, String category,
                                             Double water, Integer labour, Double budget) {
        AgriculturalAction a = new AgriculturalAction(id, name, category, "d",
                water, labour, budget, "Rice", "Vegetative",
                "REFERENCE_SUPPORTED", "Extension manual p.12", true);
        return a;
    }

    private static ConstraintValidationResponse feasible(String name) {
        return new ConstraintValidationResponse(true, "All applicability and resource checks passed.",
                1L, name, "FEASIBLE", List.of(
                        new ConstraintResult("WATER", "FEASIBLE", "ok"),
                        new ConstraintResult("BUDGET", "FEASIBLE", "ok")));
    }

    private static FarmState state(double water, int labour, double budget) {
        FarmState s = new FarmState();
        s.setWaterAvailable(water);
        s.setLabourAvailable(labour);
        s.setBudgetAvailable(budget);
        return s;
    }

    private static RiskAnalysisResponse risk(RiskFinding... findings) {
        return new RiskAnalysisResponse(true, "m", 1L, "Rice", "Vegetative",
                findings.length == 0 ? "LOW" : "HIGH", List.of(findings));
    }

    @Test
    void combinedBudgetOverrunDefersTheLowerRankedAction() {
        AgriculturalAction a = action(1, "A", "Irrigation", 100.0, 1, 3000.0);
        AgriculturalAction b = action(2, "B", "Fertilizer", 0.0, 1, 4000.0);

        List<RankedAction> ranked = prioritizer.prioritize(List.of(
                new ActionPrioritizer.Candidate(b, feasible("B")),
                new ActionPrioritizer.Candidate(a, feasible("A"))),
                risk(), state(500, 3, 5000)).actions();

        assertEquals("A", ranked.get(0).actionName());
        assertEquals(ActionPrioritizer.RECOMMENDED, ranked.get(0).decision());
        assertEquals(1, ranked.get(0).sequence());

        RankedAction deferred = ranked.get(1);
        assertEquals(ActionPrioritizer.DEFERRED, deferred.decision());
        assertNull(deferred.sequence());
        assertTrue(deferred.reasons().get(0).contains("budget (needs 4000, 2000 of 5000 left"));
        assertEquals("RESOURCE", deferred.dependencies().get(0).type());
        assertEquals(1L, deferred.dependencies().get(0).dependsOnActionId());
    }

    @Test
    void actionAddressingHighRiskOutranksCheaperUnrelatedAction() {
        AgriculturalAction irrigation = action(1, "Irrigate", "Irrigation", 100.0, 1, 900.0);
        AgriculturalAction fert = action(2, "Fertilize", "Fertilizer", 0.0, 1, 100.0);

        ActionPrioritizer.Result result = prioritizer.prioritize(List.of(
                new ActionPrioritizer.Candidate(fert, feasible("Fertilize")),
                new ActionPrioritizer.Candidate(irrigation, feasible("Irrigate"))),
                risk(new RiskFinding("WATER", "Soil Moisture", "12", "HIGH", "Soil moisture is low", "IDENTIFIED")),
                state(500, 3, 5000));

        List<RankedAction> ranked = result.actions();
        assertEquals("Irrigate", ranked.get(0).actionName());
        assertEquals(30, ranked.get(0).priorityScore());
        assertTrue(ranked.get(0).addressedRisks().get(0).startsWith("HIGH WATER risk"));
        assertEquals(2, ranked.get(1).sequence());
        assertEquals("SEQUENCE", ranked.get(1).dependencies().get(0).type());

        assertEquals(1000.0, result.resources().budgetAllocated());
        assertEquals(100.0, result.resources().waterAllocated());
        assertEquals(1, result.resources().peakLabourAllocated());
    }

    @Test
    void infeasibleUnknownAndUnreferencedActionsCarryReasons() {
        AgriculturalAction over = action(1, "Over", "Fertilizer", 0.0, 1, 9000.0);
        AgriculturalAction missing = action(2, "Missing", "Pest Control", null, null, null);
        AgriculturalAction unreferenced = action(3, "Unref", "Irrigation", 1.0, 1, 1.0);
        unreferenced.setEvidenceStatus("PLACEHOLDER");
        AgriculturalAction ok = action(4, "Ok", "Irrigation", 1.0, 1, 1.0);

        ConstraintValidationResponse overBudget = new ConstraintValidationResponse(true,
                "One or more constraints are not satisfied.", 1L, "Over", "INFEASIBLE", List.of(
                        new ConstraintResult("BUDGET", "INFEASIBLE",
                                "Insufficient budget. Required: 9000.0; available: 5000.0.")));
        ConstraintValidationResponse unknown = new ConstraintValidationResponse(true,
                "Some of your farm details are missing. Add them on the Farm State page.", 1L, "Missing", "NEEDS_INPUT",
                List.of(new ConstraintResult("WATER", "NEEDS_INPUT", "Add how much water you have on the Farm State page so this can be checked.")));

        List<RankedAction> ranked = prioritizer.prioritize(List.of(
                new ActionPrioritizer.Candidate(over, overBudget),
                new ActionPrioritizer.Candidate(missing, unknown),
                new ActionPrioritizer.Candidate(unreferenced, feasible("Unref")),
                new ActionPrioritizer.Candidate(ok, feasible("Ok"))),
                risk(), state(500, 3, 5000)).actions();

        assertEquals(List.of("Ok", "Missing", "Unref", "Over"),
                ranked.stream().map(RankedAction::actionName).toList());
        assertEquals(List.of(1, 2, 3, 4), ranked.stream().map(RankedAction::rank).toList());

        RankedAction needsData = ranked.get(1);
        assertEquals(ActionPrioritizer.NEEDS_DATA, needsData.decision());
        assertEquals("DATA", needsData.dependencies().get(0).type());
        assertTrue(needsData.reasons().get(0).contains("Add how much water you have"));

        RankedAction rejected = ranked.get(3);
        assertEquals(ActionPrioritizer.REJECTED, rejected.decision());
        assertTrue(rejected.reasons().get(0).contains("Rejected by BUDGET: Insufficient budget"));

        RankedAction unref = ranked.get(2);
        assertEquals(ActionPrioritizer.REJECTED, unref.decision());
        assertTrue(unref.reasons().get(0).contains("no verified agronomic reference"));
        assertNull(unref.sequence());
    }

    @Test
    void categoryMappingOnlyMatchesKnownLabels() {
        assertEquals(List.of("WATER"), ActionPrioritizer.riskCategoriesFor("Irrigation"));
        assertEquals(List.of("SOIL"), ActionPrioritizer.riskCategoriesFor("Fertilizer"));
        assertEquals(List.of("DISEASE"), ActionPrioritizer.riskCategoriesFor("Pest Control"));
        assertEquals(List.of(), ActionPrioritizer.riskCategoriesFor("Harvesting"));
        assertEquals(List.of(), ActionPrioritizer.riskCategoriesFor(null));
    }
}
