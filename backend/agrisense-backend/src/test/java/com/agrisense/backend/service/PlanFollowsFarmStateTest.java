package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agrisense.backend.dto.ActionPlanResponse;
import com.agrisense.backend.dto.SeasonPlanResponse.Due;
import com.agrisense.backend.dto.SeasonPlanResponse.StagePlan;
import com.agrisense.backend.dto.SeasonPlanResponse.StageStep;
import com.agrisense.backend.dto.StepRequirements;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.validation.FarmStateValidator;

/*
 * The action plan follows the farm state: a saved plan is replaced by a
 * new draft once the farm state changes, the plan shows the crop and
 * stage it was made for, and step due dates come from the farm's
 * sowing or transplanting date.
 */
class PlanFollowsFarmStateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);
    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);

    private final ActionPlanServiceImpl service = new ActionPlanServiceImpl(
            plans, mock(AgriculturalActionRepository.class), farms, states,
            mock(ConstraintValidationService.class),
            mock(RiskAnalysisService.class),
            mock(ActionPrioritizer.class),
            readings);

    private static Farm farm() {
        Farm f = new Farm();
        f.setId(1L);
        f.setFarmName("Test paddy");
        f.setCrop("rice");
        f.setGrowthStage("Seedling");
        return f;
    }

    private static ActionPlan plan(Farm f, LocalDateTime at) {
        ActionPlan p = new ActionPlan();
        p.setId(7L);
        p.setFarm(f);
        p.setStatus("DRAFT");
        p.setCreatedAt(at);
        return p;
    }

    private static FarmStateReading reading(LocalDateTime at, String crop, String stage) {
        FarmStateReading r = new FarmStateReading();
        r.setRecordedAt(at);
        r.setCrop(crop);
        r.setGrowthStage(stage);
        return r;
    }

    @Test
    void latestPlanIsKeptWhenFarmStateHasNotChangedSince() {
        Farm f = farm();
        LocalDateTime made = LocalDateTime.of(2026, 11, 25, 10, 0);
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(plan(f, made)));
        when(readings.findFirstByFarmIdOrderByRecordedAtDesc(1L))
                .thenReturn(Optional.of(reading(made.minusMinutes(5), "rice", "Flowering")));
        when(readings.findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(any(), any()))
                .thenReturn(Optional.of(reading(made.minusMinutes(5), "rice", "Flowering")));

        ActionPlanResponse r = service.currentPlan(1L);

        assertEquals(7L, r.getId());
        // Crop and stage as the farm state had them, not the farm record.
        assertEquals("Flowering", r.getGrowthStage());
        verify(states, never()).findByFarm(any());
    }

    @Test
    void newDraftIsTriedWhenFarmStateChangedAfterThePlan() {
        Farm f = farm();
        LocalDateTime made = LocalDateTime.of(2026, 11, 25, 10, 0);
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(plan(f, made)));
        when(readings.findFirstByFarmIdOrderByRecordedAtDesc(1L))
                .thenReturn(Optional.of(reading(made.plusHours(2), "rice", "Maturity")));
        when(farms.findById(1L)).thenReturn(Optional.of(f));
        // No farm state: generating fails, so the latest saved plan is returned.
        when(states.findByFarm(f)).thenReturn(Optional.empty());

        ActionPlanResponse r = service.currentPlan(1L);

        verify(states).findByFarm(f);
        assertEquals(7L, r.getId());
    }

    @Test
    void stepDueDatesCountFromTheFarmsTransplantingDate() {
        FarmState s = new FarmState();
        s.setTransplantingDate(TODAY.minusDays(4));

        Due weeding = SeasonPlanServiceImpl.due(req(3, 5, "TRANSPLANTING", "3 to 5 days after transplanting"), s, TODAY);
        assertEquals("DUE_NOW", weeding.status());
        assertEquals(TODAY.minusDays(1), weeding.from());
        assertEquals(TODAY.plusDays(1), weeding.to());
        assertTrue(weeding.sentence().contains("day 4 after transplanting"));

        Due later = SeasonPlanServiceImpl.due(req(15, 15, "TRANSPLANTING", "15 days after transplanting"), s, TODAY);
        assertEquals("COMING", later.status());
        assertEquals(TODAY.plusDays(11), later.from());

        Due past = SeasonPlanServiceImpl.due(req(0, 0, "TRANSPLANTING", "On transplanting day"), s, TODAY);
        assertEquals("PASSED", past.status());

        Due open = SeasonPlanServiceImpl.due(req(0, null, "TRANSPLANTING", "Until established"), s, TODAY);
        assertEquals("STARTED", open.status());
        assertNull(open.to());
    }

    @Test
    void noDateIsGuessedWhenTheGuideOrTheFarmHasNone() {
        FarmState s = new FarmState();

        assertEquals("ADD_DATE",
                SeasonPlanServiceImpl.due(req(30, 35, "SOWING", "30-35 days after sowing"), s, TODAY).status());
        assertEquals("BY_STAGE",
                SeasonPlanServiceImpl.due(req(null, null, "TRANSPLANTING", "At active tillering"), s, TODAY).status());

        s.setSowingDate(TODAY.minusDays(12));
        Due emergence = SeasonPlanServiceImpl.due(req(10, 10, "EMERGENCE", "10 days after the seedlings come up"), s, TODAY);
        assertEquals("BY_STAGE", emergence.status());
        assertNull(emergence.from());

        assertNull(SeasonPlanServiceImpl.due(null, s, TODAY));
    }

    @Test
    void aStepOfAnotherStageThatIsDueByDateIsPointedOut() {
        FarmState s = new FarmState();
        s.setSowingDate(TODAY.minusDays(32));
        Due dueNow = new Due("DUE_NOW", TODAY.minusDays(2), TODAY.plusDays(3), "Due now");
        StageStep knee = new StageStep(1L, "Top-dress nitrogen at knee-high stage", "Fertilizer",
                "", false, "src", null, dueNow);
        List<StagePlan> stages = List.of(
                new StagePlan("Seedling", "DONE", List.of(new StageStep(2L, "Old step", "Water",
                        "", false, "src", null, dueNow))),
                new StagePlan("Seedling", "NOW", List.of()),
                new StagePlan("Vegetative", "UPCOMING", List.of(knee)));

        List<String> notes = SeasonPlanServiceImpl.dateNotes("maize", s, stages);

        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("Vegetative stage"));
    }

    @Test
    void sowingDateCannotBeInTheFutureOrAfterTransplanting() {
        FarmState s = new FarmState();
        s.setSowingDate(LocalDate.now().plusDays(30));
        assertThrows(InvalidRequestException.class, () -> FarmStateValidator.validate(s));

        FarmState t = new FarmState();
        t.setSowingDate(LocalDate.now().minusDays(10));
        t.setTransplantingDate(LocalDate.now().minusDays(20));
        assertThrows(InvalidRequestException.class, () -> FarmStateValidator.validate(t));

        FarmState ok = new FarmState();
        ok.setSowingDate(LocalDate.now().minusDays(40));
        ok.setTransplantingDate(LocalDate.now().minusDays(15));
        FarmStateValidator.validate(ok);
    }

    private static StepRequirements req(Integer from, Integer to, String countFrom, String text) {
        return new StepRequirements(1.0, "Rabi",
                new StepRequirements.Timing(text, from, to, countFrom, "src"),
                List.of(), null, null, null, List.of(), List.of(), List.of(), null);
    }
}
