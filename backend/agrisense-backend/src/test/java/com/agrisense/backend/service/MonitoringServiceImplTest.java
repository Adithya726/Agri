package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.agrisense.backend.dto.ActionExecutionRequest;
import com.agrisense.backend.dto.MonitoringResponse;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanItemRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.security.OwnershipGuard;

class MonitoringServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 20, 12, 0);

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);
    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);
    private final ActionPlanItemRepository items = mock(ActionPlanItemRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);
    private final OwnershipGuard guard = mock(OwnershipGuard.class);

    private final MonitoringServiceImpl service = new MonitoringServiceImpl(
            farms, states, readings, plans, items, executions, guard);

    private Farm farm;
    private ActionPlan plan;
    private ActionPlanItem item;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

        farm = new Farm();
        farm.setId(1L);
        farm.setFarmName("Plot");

        AgriculturalAction action = new AgriculturalAction();
        action.setActionName("Irrigate");
        action.setCategory("Irrigation");

        plan = new ActionPlan();
        plan.setId(10L);
        plan.setFarm(farm);
        plan.setStatus("ACCEPTED");
        plan.setCreatedAt(NOW.minusDays(5));

        item = new ActionPlanItem();
        item.setId(100L);
        item.setActionPlan(plan);
        item.setAgriculturalAction(action);
        item.setSequenceOrder(1);
        item.setValidationStatus("UNKNOWN");
        item.setValidationMessage("NEEDS_DATA: water unknown");
        plan.getItems().add(item);

        when(items.findById(100L)).thenReturn(Optional.of(item));
        when(guard.requireActionPlan(10L)).thenReturn(plan);
        when(farms.findById(1L)).thenReturn(Optional.of(farm));
        when(states.findByFarm(farm)).thenReturn(Optional.empty());
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(plan));
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of());
        when(executions.findByActionPlanItemActionPlanId(10L)).thenReturn(List.of());
        when(executions.findByActionPlanItemId(100L)).thenReturn(Optional.empty());
    }

    private static FarmStateReading reading(long id, LocalDateTime at, double moisture, String health) {
        FarmStateReading r = new FarmStateReading();
        r.setId(id);
        r.setRecordedAt(at);
        r.setSoilMoisture(moisture);
        r.setSoilPh(6.5);
        r.setCropHealth(health);
        return r;
    }

    private static ActionExecutionRequest request(String status, LocalDate date) {
        ActionExecutionRequest r = new ActionExecutionRequest();
        r.setStatus(status);
        r.setExecutedOn(date);
        return r;
    }

    @Test
    void compareReportsOnlyChangedValuesWithDifferences() {
        List<MonitoringResponse.FieldChange> changes = MonitoringServiceImpl.compare(
                reading(1, NOW, 20.0, "Moderate"), reading(2, NOW, 32.5, "Healthy"));

        assertEquals(2, changes.size());
        assertEquals("soilMoisture", changes.get(0).field());
        assertEquals(12.5, changes.get(0).difference());
        assertEquals("cropHealth", changes.get(1).field());
        assertNull(changes.get(1).difference());
    }

    @Test
    void changeSinceComparesLastReadingBeforeTheActionWithTheNewest() {
        ActionExecution e = new ActionExecution();
        e.setStatus(ActionExecution.DONE);
        e.setExecutedOn(NOW.toLocalDate().minusDays(2));
        e.setRecordedAt(NOW.minusDays(1));

        FarmStateReading before = reading(1, NOW.minusDays(3), 20.0, "Healthy");
        FarmStateReading latest = reading(2, NOW.minusHours(1), 30.0, "Healthy");
        when(readings.findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(
                eq(1L), eq(NOW.toLocalDate().minusDays(1).atStartOfDay())))
                .thenReturn(Optional.of(before));

        MonitoringResponse.ChangeSince change = service.changeSince(1L, e, latest);

        assertEquals(1, change.changes().size());
        assertEquals(10.0, change.changes().get(0).difference());
    }

    @Test
    void changeSinceSaysSoWhenNothingWasRecordedAfterTheAction() {
        ActionExecution e = new ActionExecution();
        e.setExecutedOn(NOW.toLocalDate());
        e.setRecordedAt(NOW);
        FarmStateReading only = reading(1, NOW.minusDays(1), 20.0, "Healthy");
        when(readings.findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(eq(1L), any()))
                .thenReturn(Optional.of(only));

        MonitoringResponse.ChangeSince change = service.changeSince(1L, e, only);

        assertTrue(change.changes().isEmpty());
        assertTrue(change.message().contains("not been updated"));
    }

    @Test
    void futureDateIsRejected() {
        assertThrows(InvalidRequestException.class, () ->
                service.recordExecution(100L, request("DONE", NOW.toLocalDate().plusDays(1))));
        verify(executions, never()).save(any());
    }

    @Test
    void dateBeforePlanAndBadStatusAreRejected() {
        assertThrows(InvalidRequestException.class, () ->
                service.recordExecution(100L, request("DONE", NOW.toLocalDate().minusDays(6))));
        assertThrows(InvalidRequestException.class, () ->
                service.recordExecution(100L, request("FINISHED", NOW.toLocalDate())));
        verify(executions, never()).save(any());
    }

    @Test
    void draftPlanItemsCannotBeReported() {
        plan.setStatus("DRAFT");

        InvalidRequestException e = assertThrows(InvalidRequestException.class, () ->
                service.recordExecution(100L, request("DONE", NOW.toLocalDate())));

        assertTrue(e.getMessage().contains("accepted plan"));
        verify(executions, never()).save(any());
    }

    @Test
    void validReportIsSavedAndCountedInProgress() {
        when(executions.save(any(ActionExecution.class))).thenAnswer(i -> {
            ActionExecution saved = i.getArgument(0);
            when(executions.findByActionPlanItemActionPlanId(10L)).thenReturn(List.of(saved));
            return saved;
        });

        MonitoringResponse r = service.recordExecution(100L, request("skipped", NOW.toLocalDate()));

        assertEquals(1, r.progress().total());
        assertEquals(1, r.progress().skipped());
        assertEquals(0, r.progress().pending());
        assertEquals("SKIPPED", r.items().get(0).execution().status());
        assertEquals("NEEDS_DATA", r.items().get(0).decision());
    }

    @Test
    void farmWithoutAcceptedPlanGetsAnEmptyPlanSection() {
        plan.setStatus("REJECTED");

        MonitoringResponse r = service.getFarmMonitoring(1L);

        assertNull(r.plan());
        assertEquals(0, r.progress().total());
        assertTrue(r.message().contains("No accepted action plan"));
    }
}
