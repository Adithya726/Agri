package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.ReplanResponse.Assessment;
import com.agrisense.backend.dto.ReplanResponse.Comparison;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.security.OwnershipGuard;

class ReplanServiceImplTest {

    private static final LocalDateTime PLAN_TIME = LocalDateTime.of(2026, 11, 20, 10, 0);

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);
    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final AgriculturalActionRepository actions = mock(AgriculturalActionRepository.class);
    private final ConstraintValidationService validation = mock(ConstraintValidationService.class);
    private final RiskAnalysisService risk = mock(RiskAnalysisService.class);
    private final ActionPrioritizer prioritizer = mock(ActionPrioritizer.class);
    private final ActionPlanService actionPlanService = mock(ActionPlanService.class);
    private final OwnershipGuard guard = mock(OwnershipGuard.class);

    private final ReplanServiceImpl service = new ReplanServiceImpl(farms, states, readings, plans,
            executions, actions, validation, risk, prioritizer, actionPlanService, guard);

    private Farm farm;
    private FarmState state;
    private ActionPlan accepted;
    private AgriculturalAction weed;
    private AgriculturalAction irrigate;

    @BeforeEach
    void setUp() {
        farm = new Farm();
        farm.setId(1L);

        state = new FarmState();
        state.setFarm(farm);
        state.setCrop("rice");
        state.setGrowthStage("Flowering");

        weed = action(1L, "Hand weed", "Vegetative");
        irrigate = action(2L, "Irrigate", "Vegetative");

        accepted = plan(10L, "ACCEPTED");
        accepted.setCreatedAt(PLAN_TIME);
        accepted.getItems().add(item(100L, accepted, weed, 1, "RECOMMENDED: fits."));
        accepted.getItems().add(item(101L, accepted, irrigate, 2, "NEEDS_DATA: water unknown"));

        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.of(state));
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(accepted));
        when(executions.findByActionPlanItemActionPlanId(any())).thenReturn(List.of());
        when(actions.findByActiveTrue()).thenReturn(List.of());
    }

    private static AgriculturalAction action(Long id, String name, String stage) {
        AgriculturalAction a = new AgriculturalAction();
        a.setId(id);
        a.setActionName(name);
        a.setCategory("Test");
        a.setApplicableCrop("rice");
        a.setApplicableGrowthStage(stage);
        a.setEvidenceStatus("REFERENCE_SUPPORTED");
        a.setSourceReference("ANGRAU");
        a.setActive(true);
        return a;
    }

    private ActionPlan plan(Long id, String status) {
        ActionPlan p = new ActionPlan();
        p.setId(id);
        p.setFarm(farm);
        p.setStatus(status);
        return p;
    }

    private static ActionPlanItem item(Long id, ActionPlan plan, AgriculturalAction action, int seq, String message) {
        ActionPlanItem i = new ActionPlanItem();
        i.setId(id);
        i.setActionPlan(plan);
        i.setAgriculturalAction(action);
        i.setSequenceOrder(seq);
        i.setValidationMessage(message);
        return i;
    }

    private static FarmStateReading reading(Long id, LocalDateTime at, String stage) {
        FarmStateReading r = new FarmStateReading();
        r.setId(id);
        r.setRecordedAt(at);
        r.setCrop("rice");
        r.setGrowthStage(stage);
        return r;
    }

    private static RankedAction ranked(Long actionId, String decision) {
        return new RankedAction(1, 1, actionId, "x", "Test", decision, "INFEASIBLE", 0,
                List.of(), List.of("Growth stage no longer matches."), List.of(), List.of(),
                null, null, null, "REFERENCE_SUPPORTED", "ANGRAU");
    }

    @Test
    void stageChangeAndNewDecisionsRecommendReplanning() {
        FarmStateReading before = reading(1L, PLAN_TIME.minusMinutes(5), "Vegetative");
        FarmStateReading now = reading(2L, PLAN_TIME.plusDays(10), "Flowering");
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(now, before));
        when(readings.findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(1L, PLAN_TIME))
                .thenReturn(Optional.of(before));

        ActionExecution done = new ActionExecution();
        done.setActionPlanItem(accepted.getItems().get(1));
        done.setStatus(ActionExecution.DONE);
        when(executions.findByActionPlanItemActionPlanId(10L)).thenReturn(List.of(done));

        when(prioritizer.prioritize(anyList(), any(), eq(state)))
                .thenReturn(new ActionPrioritizer.Result(List.of(ranked(1L, "REJECTED")), null));

        Assessment a = service.assess(1L);

        assertTrue(a.stageChanged());
        assertFalse(a.cropChanged());
        assertTrue(a.replanRecommended());
        assertEquals("REJECTED", a.impacts().get(0).currentDecision());
        assertTrue(a.impacts().get(0).changed());
        assertEquals("DONE", a.impacts().get(1).executionStatus());
        assertNull(a.impacts().get(1).currentDecision());
    }

    @Test
    void unchangedFarmKeepsThePlan() {
        state.setGrowthStage("Vegetative");
        FarmStateReading only = reading(1L, PLAN_TIME.minusMinutes(5), "Vegetative");
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(only));
        when(readings.findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(1L, PLAN_TIME))
                .thenReturn(Optional.of(only));
        when(prioritizer.prioritize(anyList(), any(), eq(state)))
                .thenReturn(new ActionPrioritizer.Result(
                        List.of(ranked(1L, "RECOMMENDED"), ranked(2L, "NEEDS_DATA")), null));

        Assessment a = service.assess(1L);

        assertFalse(a.replanRecommended());
        assertTrue(a.changes().isEmpty());
        assertTrue(a.reasons().get(0).contains("still stands"));
    }

    @Test
    void noAcceptedPlanIsReportedWithoutError() {
        accepted.setStatus("REJECTED");

        Assessment a = service.assess(1L);

        assertNull(a.plan());
        assertFalse(a.replanRecommended());
    }

    @Test
    void comparisonSortsActionsIntoKeptAddedAndRemoved() {
        AgriculturalAction spray = action(3L, "Spray", "Flowering");
        ActionPlan draft = plan(11L, "DRAFT");
        draft.getItems().add(item(200L, draft, weed, 1, "REJECTED: stage."));
        draft.getItems().add(item(201L, draft, spray, 2, "NEEDS_DATA: water."));

        ActionExecution done = new ActionExecution();
        done.setActionPlanItem(accepted.getItems().get(1));
        done.setStatus(ActionExecution.DONE);
        when(executions.findByActionPlanItemActionPlanId(10L)).thenReturn(List.of(done));

        when(guard.requireActionPlan(10L)).thenReturn(accepted);
        when(guard.requireActionPlan(11L)).thenReturn(draft);

        Comparison c = service.compare(10L, 11L);

        assertEquals(1, c.kept().size());
        assertEquals("RECOMMENDED", c.kept().get(0).currentDecision());
        assertEquals("REJECTED", c.kept().get(0).newDecision());
        assertEquals(1, c.added().size());
        assertEquals("Spray", c.added().get(0).actionName());
        assertEquals(1, c.removed().size());
        assertEquals("DONE", c.removed().get(0).executionStatus());
        assertTrue(c.summary().get(2).contains("already done"));
    }

    @Test
    void plansOfDifferentFarmsCannotBeCompared() {
        Farm other = new Farm();
        other.setId(2L);
        ActionPlan foreign = plan(12L, "DRAFT");
        foreign.setFarm(other);
        when(guard.requireActionPlan(10L)).thenReturn(accepted);
        when(guard.requireActionPlan(12L)).thenReturn(foreign);

        assertThrows(InvalidRequestException.class, () -> service.compare(10L, 12L));
        assertThrows(InvalidRequestException.class, () -> service.compare(10L, 10L));
    }

    @Test
    void replanWithoutAcceptedPlanIsRejected() {
        accepted.setStatus("DRAFT");

        assertThrows(InvalidRequestException.class, () -> service.replan(1L));
    }
}
