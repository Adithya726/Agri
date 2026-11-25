package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agrisense.backend.dto.ActionPlanItemResponse;
import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.AssistantResponse;
import com.agrisense.backend.dto.MonitoringResponse;
import com.agrisense.backend.dto.MonitoringResponse.Execution;
import com.agrisense.backend.dto.MonitoringResponse.MonitoredItem;
import com.agrisense.backend.dto.MonitoringResponse.PlanSummary;
import com.agrisense.backend.dto.MonitoringResponse.Progress;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;

class FarmAssistantServiceImplTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);
    private final MonitoringService monitoring = mock(MonitoringService.class);
    private final ActionPlanService plans = mock(ActionPlanService.class);
    private final RiskAnalysisService risk = mock(RiskAnalysisService.class);

    private final FarmAssistantServiceImpl service = new FarmAssistantServiceImpl(
            farms, states, readings, monitoring, plans, risk);

    private Farm farm;

    @BeforeEach
    void setUp() {
        farm = new Farm();
        farm.setId(1L);
        when(farms.findById(1L)).thenReturn(Optional.of(farm));
    }

    @Test
    void detectsIntentsFromKeywords() {
        assertEquals("NEXT_ACTION", FarmAssistantServiceImpl.detectIntent("What should I do next?"));
        assertEquals("WHY_REJECTED", FarmAssistantServiceImpl.detectIntent("Why was the weeding rejected?"));
        assertEquals("PLAN_PROGRESS", FarmAssistantServiceImpl.detectIntent("How is my plan progressing?"));
        assertEquals("RISKS", FarmAssistantServiceImpl.detectIntent("What are the current risks?"));
        assertEquals("WHAT_CHANGED", FarmAssistantServiceImpl.detectIntent("What changed in my farm state?"));
        assertEquals("CURRENT_STATE", FarmAssistantServiceImpl.detectIntent("What is my soil pH?"));
        assertEquals("RISKS", FarmAssistantServiceImpl.detectIntent("मेरे खेत में क्या जोखिम है?"));
        assertEquals("NEXT_ACTION", FarmAssistantServiceImpl.detectIntent("తర్వాత ఏమి చేయాలి?"));
        // Agronomy advice is not in the data, so it is not matched.
        assertEquals("UNKNOWN", FarmAssistantServiceImpl.detectIntent("How much urea should I apply per acre?"));
        assertEquals("UNKNOWN", FarmAssistantServiceImpl.detectIntent("Which phone is best?"));
    }

    @Test
    void nextActionIsTheFirstPendingRecommendedItemOfTheAcceptedPlan() {
        when(monitoring.getFarmMonitoring(1L)).thenReturn(monitoringWithPlan());
        when(plans.getPlanById(5L)).thenReturn(plan(5L, "ACCEPTED", List.of(
                item(51L, "Hand weed", 1, "RECOMMENDED", "RECOMMENDED: All checks passed."),
                item(52L, "Apply nitrogen", 2, "RECOMMENDED", "RECOMMENDED: Addresses MEDIUM SOIL risk."))));

        AssistantResponse a = service.ask(1L, "What should I do next?");

        assertEquals("NEXT_ACTION", a.intent());
        assertTrue(a.answered());
        assertTrue(a.answer().contains("Apply nitrogen"));
        assertTrue(a.facts().contains("RECOMMENDED: Addresses MEDIUM SOIL risk."));
        assertEquals(List.of("Accepted plan #5"), a.sources());
    }

    @Test
    void nextActionWithoutAnyPlanSaysSo() {
        when(monitoring.getFarmMonitoring(1L)).thenReturn(new MonitoringResponse(1L, "F", "Rice", "Vegetative",
                null, new Progress(0, 0, 0, 0), List.of(), List.of(), "No accepted action plan yet."));
        when(plans.getPlansByFarmId(1L)).thenReturn(List.of());

        AssistantResponse a = service.ask(1L, "what to do");
        assertTrue(a.answer().startsWith("There is no accepted action plan"));
    }

    @Test
    void whyRejectedQuotesTheStoredValidationReasons() {
        when(plans.getPlansByFarmId(1L)).thenReturn(List.of(
                plan(6L, "REJECTED", List.of()),
                plan(5L, "ACCEPTED", List.of(
                        item(51L, "Hand weed", 1, "RECOMMENDED", "RECOMMENDED: ok"),
                        item(53L, "Spray fungicide", 3, "REJECTED",
                                "REJECTED: Rejected by BUDGET: Insufficient budget. Required: 900.0; available: 500.0.")))));

        AssistantResponse a = service.ask(1L, "Why was an action rejected?");

        assertEquals("WHY_REJECTED", a.intent());
        assertTrue(a.facts().stream().anyMatch(f -> f.contains("Insufficient budget")));
        assertTrue(a.facts().stream().anyMatch(f -> f.contains("Plan #6 was rejected by you")));
    }

    @Test
    void progressUsesMonitoringCountsAndNotes() {
        when(monitoring.getFarmMonitoring(1L)).thenReturn(monitoringWithPlan());
        AssistantResponse a = service.ask(1L, "how is the progress");
        assertEquals("PLAN_PROGRESS", a.intent());
        assertTrue(a.answer().contains("1 of 2 actions reported (50%)"));
        assertTrue(a.facts().stream().anyMatch(f -> f.contains("\"East side done\"")));
    }

    @Test
    void risksComeFromRiskAnalysisHighestFirst() {
        when(risk.analyzeFarmRisks(1L)).thenReturn(new RiskAnalysisResponse(true, "Review.", 1L, "Rice",
                "Vegetative", "HIGH", List.of(
                        new RiskAnalysisResponse.RiskFinding("WEATHER", "Humidity", "90", "MEDIUM", "Humidity is high", "IDENTIFIED"),
                        new RiskAnalysisResponse.RiskFinding("WATER", "Soil Moisture", "15", "HIGH", "Soil moisture is low", "IDENTIFIED"))));
        AssistantResponse a = service.ask(1L, "Any risks?");
        assertTrue(a.answer().startsWith("Overall risk is HIGH"));
        assertTrue(a.facts().get(0).startsWith("HIGH WATER"));
    }

    @Test
    void risksWithoutStateAreNotAnswered() {
        when(risk.analyzeFarmRisks(1L)).thenThrow(new ResourceNotFoundException("Farm state not found"));
        assertFalse(service.ask(1L, "risks").answered());
    }

    @Test
    void whatChangedComparesTheLastTwoReadings() {
        FarmStateReading older = reading(1L, T.minusDays(3), 30.0);
        FarmStateReading newer = reading(2L, T, 18.0);
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(newer, older));
        AssistantResponse a = service.ask(1L, "What changed?");
        assertEquals("WHAT_CHANGED", a.intent());
        assertEquals(List.of("soilMoisture: 30 → 18"), a.facts());
    }

    @Test
    void currentStateAnswersOnlyTheFieldAsked() {
        when(states.findByFarm(farm)).thenReturn(Optional.of(new FarmState(farm, "Rice", "Vegetative",
                30.0, 6.5, 80.0, 40.0, 40.0, 30.0, 70.0, 100.0, 500.0, 3, 5000.0, "Good", "None")));
        when(readings.findFirstByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(Optional.empty());
        AssistantResponse a = service.ask(1L, "What is my soil pH?");
        assertEquals(List.of("soil pH: 6.5"), a.facts());
    }

    @Test
    void unknownQuestionIsNotAnsweredAndBlankIsBadRequest() {
        AssistantResponse a = service.ask(1L, "How much urea should I apply per acre?");
        assertEquals("UNKNOWN", a.intent());
        assertFalse(a.answered());
        assertTrue(a.answer().contains("not covered"));
        assertThrows(InvalidRequestException.class, () -> service.ask(1L, "  "));
        assertThrows(InvalidRequestException.class, () -> service.ask(1L, "x".repeat(501)));
    }

    private static MonitoringResponse monitoringWithPlan() {
        return new MonitoringResponse(1L, "F", "Rice", "Vegetative",
                new PlanSummary(5L, "ACCEPTED", T.minusDays(2), T.minusDays(2)),
                new Progress(2, 1, 0, 1),
                List.of(
                        new MonitoredItem(51L, 1, "Hand weed", "Weed management", "RECOMMENDED",
                                new Execution("DONE", LocalDate.of(2026, 11, 19), "East side done", T), null),
                        new MonitoredItem(52L, 2, "Apply nitrogen", "Nutrient management", "RECOMMENDED",
                                null, null)),
                List.of(), "1 of 2 actions are still pending.");
    }

    private static ActionPlanResponse plan(Long id, String status, List<ActionPlanItemResponse> items) {
        return new ActionPlanResponse(id, 1L, "F", "Rice", "Vegetative", status, T.minusDays(2),
                T.minusDays(1), items);
    }

    private static ActionPlanItemResponse item(Long id, String name, int seq, String decision, String message) {
        return new ActionPlanItemResponse(id, id * 10, name, "Category", "d", seq, "FEASIBLE", message)
                .withEvidence(decision, "REFERENCE_SUPPORTED", "ANGRAU RARS Tirupati");
    }

    private static FarmStateReading reading(Long id, LocalDateTime at, Double moisture) {
        FarmStateReading r = new FarmStateReading();
        r.setId(id);
        r.setRecordedAt(at);
        r.setCrop("Rice");
        r.setSoilMoisture(moisture);
        return r;
    }
}
