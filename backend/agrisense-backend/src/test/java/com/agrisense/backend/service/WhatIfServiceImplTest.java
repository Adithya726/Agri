package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.CropRecommendationResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.WhatIfRequest;
import com.agrisense.backend.dto.WhatIfResponse;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

/*
 * Uses the real ActionPrioritizer, so the what-if column is ranked
 * exactly as a plan would be. Risk, validation and ML are mocked.
 */
class WhatIfServiceImplTest {

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final AgriculturalActionRepository actions = mock(AgriculturalActionRepository.class);
    private final RiskAnalysisService risk = mock(RiskAnalysisService.class);
    private final ConstraintValidationService validation = mock(ConstraintValidationService.class);
    private final CropRecommendationService crop = mock(CropRecommendationService.class);
    private final YieldPredictionService yield = mock(YieldPredictionService.class);

    private final WhatIfServiceImpl service = new WhatIfServiceImpl(farms, states, actions, risk,
            validation, new ActionPrioritizer(), crop, yield);

    private Farm farm;
    private FarmState saved;

    @BeforeEach
    void setUp() {
        farm = new Farm();
        farm.setId(1L);
        farm.setFarmName("Test farm");
        saved = new FarmState(farm, "Rice", "Vegetative", 30.0, 6.5, 80.0, 40.0, 40.0,
                30.0, 70.0, 100.0, 500.0, 3, 5000.0, "Good", "None");
        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.of(saved));

        AgriculturalAction irrigate = action(1L, "Irrigate", "Irrigation", "Vegetative");
        AgriculturalAction weed = action(2L, "Hand weed", "Weed management", "Vegetative");
        AgriculturalAction drain = action(3L, "Drain field", "Water management", "Flowering");
        when(actions.findByActiveTrue()).thenReturn(List.of(irrigate, weed, drain));

        when(risk.analyzeFarmRisks(eq(farm), any(FarmState.class))).thenAnswer(inv -> {
            FarmState s = inv.getArgument(1);
            boolean dry = s.getSoilMoisture() != null && s.getSoilMoisture() < 20;
            return new RiskAnalysisResponse(true, "m", 1L, s.getCrop(), s.getGrowthStage(),
                    dry ? "HIGH" : "LOW",
                    dry ? List.of(new RiskAnalysisResponse.RiskFinding("WATER", "Soil Moisture",
                            String.valueOf(s.getSoilMoisture()), "HIGH", "Soil moisture is low", "IDENTIFIED"))
                            : List.of());
        });

        // Feasible when the copy has at least 100 water.
        when(validation.validateAction(anyLong(), any(FarmState.class), any(AgriculturalAction.class)))
                .thenAnswer(inv -> {
                    FarmState s = inv.getArgument(1);
                    AgriculturalAction a = inv.getArgument(2);
                    boolean ok = s.getWaterAvailable() != null && s.getWaterAvailable() >= 100;
                    List<ConstraintResult> c = new ArrayList<>();
                    c.add(new ConstraintResult("WATER", ok ? "FEASIBLE" : "INFEASIBLE",
                            ok ? "ok" : "Insufficient water."));
                    return new ConstraintValidationResponse(true, "v", 1L, a.getActionName(),
                            ok ? "FEASIBLE" : "INFEASIBLE", c);
                });

        when(crop.recommendCrop(eq(1L), any(FarmState.class))).thenAnswer(inv -> {
            FarmState s = inv.getArgument(1);
            return new CropRecommendationResponse(true, "ok", 1L,
                    s.getRainfall() > 150 ? "rice" : "maize",
                    List.of(new CropRecommendationResponse.TopRecommendation("x", 0.5)));
        });
        when(yield.predictYield(1L)).thenThrow(
                new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Yield Prediction API is unavailable"));
    }

    @Test
    void runsPipelineOnEditedCopyAndLeavesSavedStateAlone() {
        WhatIfRequest r = new WhatIfRequest();
        r.setSoilMoisture(15.0);
        r.setRainfall(200.0);

        WhatIfResponse w = service.analyze(1L, r);

        assertEquals("LOW", w.current().overallRisk());
        assertEquals("HIGH", w.whatIf().overallRisk());
        assertEquals(2, w.changedFields().size());
        // Irrigation addresses the HIGH water risk, so it ranks first in the what-if run.
        assertEquals("Irrigate", w.whatIf().actions().get(0).actionName());
        assertEquals(30, w.whatIf().actions().get(0).priorityScore());
        assertEquals("maize", w.current().cropRecommendation().recommendedCrop());
        assertEquals("rice", w.whatIf().cropRecommendation().recommendedCrop());
        assertEquals(Boolean.TRUE, w.whatIf().cropRecommendation().matchesCurrentCrop());
        // The saved entity is untouched and nothing is written.
        assertEquals(30.0, saved.getSoilMoisture());
        assertEquals(100.0, saved.getRainfall());
        verify(states, never()).save(any());
    }

    @Test
    void lessWaterTurnsRecommendedActionsIntoRejections() {
        WhatIfRequest r = new WhatIfRequest();
        r.setWaterAvailable(50.0);

        WhatIfResponse w = service.analyze(1L, r);

        assertEquals(2, w.current().recommended());
        assertEquals(0, w.whatIf().recommended());
        assertEquals(2, w.whatIf().rejected());
        assertEquals(2, w.actionDifferences().size());
        assertEquals("RECOMMENDED", w.actionDifferences().get(0).currentDecision());
        assertEquals("REJECTED", w.actionDifferences().get(0).whatIfDecision());
    }

    @Test
    void changingStageSwitchesTheApplicableActions() {
        WhatIfRequest r = new WhatIfRequest();
        r.setGrowthStage("Flowering");

        WhatIfResponse w = service.analyze(1L, r);

        assertEquals(2, w.current().evaluatedActions());
        assertEquals(1, w.whatIf().evaluatedActions());
        assertEquals("Drain field", w.whatIf().actions().get(0).actionName());
        // Two dropped, one added.
        assertEquals(3, w.actionDifferences().size());
        assertNull(w.actionDifferences().get(2).currentDecision());
    }

    @Test
    void yieldIsReportedUnavailableWithTheReasonAndExplained() {
        WhatIfResponse w = service.analyze(1L, new WhatIfRequest());
        assertEquals("UNAVAILABLE", w.yieldPrediction().status());
        assertTrue(w.yieldPrediction().message().contains("unavailable"));
        assertTrue(w.notes().contains(WhatIfServiceImpl.YIELD_NOTE));
        assertTrue(w.changedFields().isEmpty());
    }

    @Test
    void invalidValuesAreRejectedByFarmStateValidator() {
        WhatIfRequest r = new WhatIfRequest();
        r.setSoilPh(15.0);
        r.setLabourAvailable(-1);
        InvalidRequestException e = assertThrows(InvalidRequestException.class, () -> service.analyze(1L, r));
        assertTrue(e.getFieldErrors().containsKey("soilPh"));
        assertTrue(e.getFieldErrors().containsKey("labourAvailable"));
        assertThrows(InvalidRequestException.class, () -> service.analyze(1L, null));
    }

    private static AgriculturalAction action(Long id, String name, String category, String stage) {
        AgriculturalAction a = new AgriculturalAction();
        a.setId(id);
        a.setActionName(name);
        a.setCategory(category);
        a.setApplicableCrop("rice");
        a.setApplicableGrowthStage(stage);
        a.setActive(true);
        a.setEvidenceStatus("REFERENCE_SUPPORTED");
        a.setSourceReference("ANGRAU RARS Tirupati");
        a.setRequiredWater(10.0);
        a.setRequiredLabour(1);
        a.setRequiredBudget(100.0);
        return a;
    }
}
