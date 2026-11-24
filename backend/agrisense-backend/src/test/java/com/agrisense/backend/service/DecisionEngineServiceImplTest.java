package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.ContextInterpretationResponse;
import com.agrisense.backend.dto.ContextInterpretationResponse.ContextItem;
import com.agrisense.backend.dto.CropRecommendationResponse;
import com.agrisense.backend.dto.CropRecommendationResponse.TopRecommendation;
import com.agrisense.backend.dto.DecisionResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

class DecisionEngineServiceImplTest {

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final AgriculturalActionRepository actions = mock(AgriculturalActionRepository.class);
    private final ContextInterpretationService context = mock(ContextInterpretationService.class);
    private final RiskAnalysisService risk = mock(RiskAnalysisService.class);
    private final ConstraintValidationService validator = mock(ConstraintValidationService.class);
    private final CropRecommendationService crop = mock(CropRecommendationService.class);
    private final YieldPredictionService yield = mock(YieldPredictionService.class);

    private final DecisionEngineServiceImpl engine = new DecisionEngineServiceImpl(
            farms, states, actions, context, risk, validator, crop, yield, new ActionPrioritizer());

    private Farm farm;

    @BeforeEach
    void setUp() {
        farm = new Farm();
        farm.setId(1L);
        farm.setFarmName("Field A");

        FarmState state = new FarmState();
        state.setFarm(farm);
        state.setCrop("Rice");
        state.setGrowthStage("Vegetative");
        state.setWaterAvailable(500.0);
        state.setLabourAvailable(3);
        state.setBudgetAvailable(5000.0);

        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.of(state));

        when(context.interpretFarmContext(1L)).thenReturn(new ContextInterpretationResponse(
                true, "ok", 1L, "Rice", "Vegetative", List.of(
                        new ContextItem("WATER", "Soil Moisture", "30", "ok", "LOW"),
                        new ContextItem("WEATHER", "Humidity", "90", "Humidity is high", "MEDIUM"),
                        new ContextItem("CROP", "Crop", "Rice", "Current crop", "INFO"))));
        when(risk.analyzeFarmRisks(1L)).thenReturn(new RiskAnalysisResponse(
                true, "m", 1L, "Rice", "Vegetative", "MEDIUM", List.of()));
    }

    private static AgriculturalAction action(long id, String crop) {
        return new AgriculturalAction(id, "Action " + id, "Irrigation", "d", 100.0, 1, 1000.0,
                crop, "Vegetative", "REFERENCE_SUPPORTED", "Manual", true);
    }

    @Test
    void combinesValidationRiskAndMlIntoRankedActions() {
        when(actions.findByActiveTrue()).thenReturn(List.of(action(1, "Rice"), action(2, "Wheat")));
        when(validator.validateAction(any(ConstraintValidationRequest.class))).thenReturn(
                new ConstraintValidationResponse(true, "All applicability and resource checks passed.",
                        1L, "Action 1", "FEASIBLE", List.of(new ConstraintResult("WATER", "FEASIBLE", "ok"))));
        when(crop.recommendCrop(1L)).thenReturn(new CropRecommendationResponse(
                true, "ok", 1L, "maize", List.of(new TopRecommendation("maize", 0.61))));
        when(yield.predictYield(1L)).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Yield Prediction API is unavailable"));

        DecisionResponse r = engine.decide(1L);

        // Only the Rice action is validated; the Wheat one is noted.
        verify(validator, times(1)).validateAction(any(ConstraintValidationRequest.class));
        assertEquals(1, r.actions().size());
        assertEquals(ActionPrioritizer.RECOMMENDED, r.actions().get(0).decision());
        assertEquals(1, r.actions().get(0).sequence());
        assertTrue(r.notes().stream().anyMatch(n -> n.startsWith("1 active action(s) for other crops")));

        assertEquals(1, r.context().medium());
        assertEquals(1, r.context().low());
        assertEquals("MEDIUM", r.overallRisk());

        assertEquals("AVAILABLE", r.ml().cropRecommendation().status());
        assertEquals("maize", r.ml().cropRecommendation().recommendedCrop());
        assertEquals(Boolean.FALSE, r.ml().cropRecommendation().matchesCurrentCrop());
        assertEquals("UNAVAILABLE", r.ml().yieldPrediction().status());
        assertNull(r.ml().yieldPrediction().predictedYieldKgPerHa());
        assertTrue(r.ml().yieldPrediction().message().contains("Yield Prediction API is unavailable"));

        assertEquals(1000.0, r.resources().budgetAllocated());
        assertEquals(5000.0, r.resources().budgetAvailable());
    }

    @Test
    void reportsEmptyKnowledgeBaseAndFailedCropModelWithoutInventingValues() {
        when(actions.findByActiveTrue()).thenReturn(List.of());
        when(crop.recommendCrop(1L)).thenReturn(new CropRecommendationResponse(
                false, "Required farm state values are missing", 1L, null, List.of()));
        when(yield.predictYield(1L)).thenReturn(null);

        DecisionResponse r = engine.decide(1L);

        assertTrue(r.actions().isEmpty());
        assertTrue(r.notes().get(0).startsWith("No active agricultural actions"));
        assertEquals("UNAVAILABLE", r.ml().cropRecommendation().status());
        assertNull(r.ml().cropRecommendation().recommendedCrop());
        assertEquals("UNAVAILABLE", r.ml().yieldPrediction().status());
        verify(validator, never()).validateAction(any());
    }

    @Test
    void missingFarmStateIsNotFound() {
        when(states.findByFarm(farm)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> engine.decide(1L));
    }
}
