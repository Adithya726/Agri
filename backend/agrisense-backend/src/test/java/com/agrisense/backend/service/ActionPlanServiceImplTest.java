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

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

class ActionPlanServiceImplTest {

    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);
    private final AgriculturalActionRepository actions = mock(AgriculturalActionRepository.class);
    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);

    private final ActionPlanServiceImpl service = new ActionPlanServiceImpl(
            plans, actions, farms, states,
            mock(ConstraintValidationService.class),
            mock(RiskAnalysisService.class),
            mock(ActionPrioritizer.class));

    @Test
    void decisionIsReadBackFromTheStoredMessage() {
        assertEquals("RECOMMENDED", ActionPlanServiceImpl.decisionOf("RECOMMENDED: fits water and budget."));
        assertEquals("NEEDS_DATA", ActionPlanServiceImpl.decisionOf("NEEDS_DATA: Cannot confirm WATER: x"));
        assertNull(ActionPlanServiceImpl.decisionOf("All applicability and resource checks passed."));
        assertNull(ActionPlanServiceImpl.decisionOf(null));
    }

    @Test
    void noPlanIsSavedWhenNoSourcedActionMatchesCropAndStage() {
        Farm farm = new Farm();
        farm.setId(1L);
        FarmState state = new FarmState();
        state.setFarm(farm);
        state.setCrop("rice");
        state.setGrowthStage("Maturity");

        AgriculturalAction placeholder = new AgriculturalAction();
        placeholder.setApplicableCrop("rice");
        placeholder.setApplicableGrowthStage("Maturity");
        placeholder.setEvidenceStatus("REFERENCE_SUPPORTED");
        placeholder.setSourceReference("Use the actual agricultural reference");
        placeholder.setActive(true);

        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.of(state));
        when(actions.findByActiveTrue()).thenReturn(List.of(placeholder));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.generateDraft(1L));

        assertTrue(e.getMessage().contains("rice at growth stage Maturity"));
        verify(plans, never()).save(any());
    }

    @Test
    void acceptingAPlanSupersedesTheFarmsPreviouslyAcceptedPlan() {
        Farm farm = new Farm();
        farm.setId(1L);

        ActionPlan old = new ActionPlan();
        old.setId(1L);
        old.setFarm(farm);
        old.setStatus("ACCEPTED");

        ActionPlan rejected = new ActionPlan();
        rejected.setId(2L);
        rejected.setFarm(farm);
        rejected.setStatus("REJECTED");

        ActionPlan draft = new ActionPlan();
        draft.setId(3L);
        draft.setFarm(farm);
        draft.setStatus("DRAFT");

        when(plans.findById(3L)).thenReturn(Optional.of(draft));
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(draft, rejected, old));
        when(plans.save(any(ActionPlan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.reviewPlan(3L, "ACCEPTED");

        assertEquals("ACCEPTED", draft.getStatus());
        assertEquals("SUPERSEDED", old.getStatus());
        assertEquals("REJECTED", rejected.getStatus());
    }
}
