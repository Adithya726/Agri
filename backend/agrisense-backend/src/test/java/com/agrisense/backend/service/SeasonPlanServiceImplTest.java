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

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;
import com.agrisense.backend.dto.SeasonPlanResponse;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;

class SeasonPlanServiceImplTest {

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);
    private final AgriculturalActionRepository actions = mock(AgriculturalActionRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final RiskAnalysisService risk = mock(RiskAnalysisService.class);

    private final ConstraintValidationServiceImpl validation =
            new ConstraintValidationServiceImpl(farms, states, actions);

    private final SeasonPlanServiceImpl service = new SeasonPlanServiceImpl(farms, states, readings,
            actions, executions, validation, risk, new ActionPrioritizer());

    private Farm farm;
    private FarmState state;

    @BeforeEach
    void setUp() {
        farm = new Farm();
        farm.setId(1L);
        farm.setFarmName("North field");

        state = new FarmState();
        state.setFarm(farm);
        state.setCrop("rice");
        state.setGrowthStage("Vegetative");
        state.setWaterAvailable(1000.0);
        state.setLabourAvailable(4);
        state.setBudgetAvailable(5000.0);
        state.setSoilMoisture(35.0);
        state.setSoilPh(6.8);
        state.setNitrogen(120.0);
        state.setPhosphorus(40.0);
        state.setPotassium(150.0);
        state.setTemperature(28.0);
        state.setHumidity(70.0);
        state.setRainfall(5.0);
        state.setCropHealth("Good");
        state.setDiseaseStatus("None");

        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.of(state));
        when(executions.findByActionPlanItemActionPlanFarmId(1L)).thenReturn(List.of());
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of());
        when(actions.findByActiveTrue()).thenReturn(List.of(
                action(1L, "Transplant", "Crop establishment", "Seedling"),
                action(2L, "Hand weed", "Weed management", "Vegetative"),
                action(3L, "Spray for sheath blight", "Disease management", "Vegetative"),
                action(4L, "Neck blast spray", "Disease management", "Flowering"),
                action(5L, "Harvest", "Harvest", "Harvest")));
        when(risk.analyzeFarmRisks(1L)).thenReturn(riskWith());
    }

    @Test
    void buildsWholeSeasonTimelineAroundCurrentStage() {
        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertEquals(6, plan.stages().size());
        assertEquals("DONE", plan.stages().get(0).status());
        assertEquals("NOW", plan.stages().get(1).status());
        assertEquals("UPCOMING", plan.stages().get(2).status());
        assertEquals(1, plan.stages().get(5).steps().size());
    }

    @Test
    void noUnknownStatusWhenSourceGivesNoResourceFigure() {
        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertEquals(2, plan.doNow().size());
        assertTrue(plan.onHold().isEmpty());
        for (SeasonPlanResponse.Step step : plan.doNow()) {
            assertEquals("Do now", step.decision());
            for (ConstraintResult c : step.checks()) {
                assertFalse("UNKNOWN".equals(c.getStatus()));
            }
        }
        assertEquals(SeasonPlanServiceImpl.ON_TRACK, plan.status());
    }

    @Test
    void diseaseRiskMovesDiseaseStepFirstAndRaisesAlert() {
        when(risk.analyzeFarmRisks(1L)).thenReturn(riskWith(
                new RiskFinding("DISEASE", "Disease Status", "Sheath blight", "HIGH",
                        "Disease detected on the crop.", "IDENTIFIED")));

        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertEquals(SeasonPlanServiceImpl.ACT_NOW, plan.status());
        assertEquals("Spray for sheath blight", plan.doNow().get(0).name());
        assertEquals(1, plan.doNow().get(0).order());
        assertEquals(1, plan.alerts().size());
        assertTrue(plan.alerts().get(0).doThis().get(0).contains("Spray for sheath blight"));
    }

    @Test
    void missingFarmValueAsksForInputInsteadOfUnknown() {
        state.setWaterAvailable(null);
        AgriculturalAction irrigate = action(6L, "Keep 2-3 cm water", "Water management", "Vegetative");
        irrigate.setRequiredWater(50.0);
        when(actions.findByActiveTrue()).thenReturn(List.of(irrigate));

        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertEquals(1, plan.onHold().size());
        assertEquals("Add details first", plan.onHold().get(0).decision());
        assertTrue(plan.missingDetails().contains("Water available"));
        assertEquals(SeasonPlanServiceImpl.ADD_DETAILS, plan.status());
    }

    @Test
    void budgetCutPutsStepOnHold() {
        AgriculturalAction spray = action(7L, "Spray", "Pest management", "Vegetative");
        spray.setRequiredBudget(8000.0);
        when(actions.findByActiveTrue()).thenReturn(List.of(spray));

        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertTrue(plan.doNow().isEmpty());
        assertEquals("Not possible now", plan.onHold().get(0).decision());
    }

    @Test
    void reportsChangesBetweenLatestReadings() {
        FarmStateReading before = reading(1L, LocalDateTime.of(2026, 11, 20, 9, 0), 40.0, "None");
        FarmStateReading after = reading(2L, LocalDateTime.of(2026, 11, 24, 9, 0), 22.0, "Sheath blight");
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(after, before));

        SeasonPlanResponse plan = service.buildSeasonPlan(1L);

        assertEquals(2, plan.changes().size());
        assertEquals("Soil moisture changed from 40 to 22 (-18).", plan.changes().get(0).sentence());
        assertTrue(plan.headline().contains("latest readings changed"));
    }

    @Test
    void cropWithoutGuideSaysSo() {
        state.setCrop("cotton");
        SeasonPlanResponse plan = service.buildSeasonPlan(1L);
        assertEquals(SeasonPlanServiceImpl.NO_GUIDE, plan.status());
        assertTrue(plan.stages().isEmpty());
    }

    @Test
    void requiresCrop() {
        state.setCrop(" ");
        assertThrows(InvalidRequestException.class, () -> service.buildSeasonPlan(1L));
    }

    @Test
    void seasonFollowsCalendar() {
        assertEquals("Kharif", SeasonPlanServiceImpl.seasonFor(LocalDate.of(2026, 7, 1)));
        assertEquals("Rabi", SeasonPlanServiceImpl.seasonFor(LocalDate.of(2026, 11, 25)));
        assertEquals("Summer", SeasonPlanServiceImpl.seasonFor(LocalDate.of(2026, 5, 1)));
    }

    private static AgriculturalAction action(Long id, String name, String category, String stage) {
        AgriculturalAction a = new AgriculturalAction();
        a.setId(id);
        a.setActionName(name);
        a.setCategory(category);
        a.setDescription(name + " as per ANGRAU.");
        a.setApplicableCrop("rice");
        a.setApplicableGrowthStage(stage);
        a.setEvidenceStatus("REFERENCE_SUPPORTED");
        a.setSourceReference("ANGRAU RARS Tirupati");
        a.setActive(true);
        return a;
    }

    private static FarmStateReading reading(Long id, LocalDateTime at, Double moisture, String disease) {
        FarmStateReading r = new FarmStateReading();
        r.setId(id);
        r.setRecordedAt(at);
        r.setCrop("rice");
        r.setGrowthStage("Vegetative");
        r.setSoilMoisture(moisture);
        r.setDiseaseStatus(disease);
        return r;
    }

    private static RiskAnalysisResponse riskWith(RiskFinding... findings) {
        return new RiskAnalysisResponse(true, "", 1L, "rice", "Vegetative",
                findings.length == 0 ? "LOW" : "HIGH", List.of(findings));
    }
}
