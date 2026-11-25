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

import com.agrisense.backend.dto.HistoryResponse;
import com.agrisense.backend.dto.HistoryResponse.HistoryEvent;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.ActionExecution;
import com.agrisense.backend.model.ActionPlan;
import com.agrisense.backend.model.ActionPlanItem;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.ActionExecutionRepository;
import com.agrisense.backend.repository.ActionPlanRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateReadingRepository;

class HistoryServiceImplTest {

    private static final LocalDateTime D1 = LocalDateTime.of(2026, 11, 1, 9, 0);

    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateReadingRepository readings = mock(FarmStateReadingRepository.class);
    private final ActionPlanRepository plans = mock(ActionPlanRepository.class);
    private final ActionExecutionRepository executions = mock(ActionExecutionRepository.class);

    private final HistoryServiceImpl service = new HistoryServiceImpl(farms, readings, plans, executions);

    private ActionPlan first;
    private ActionPlan second;
    private ActionPlan rejected;

    @BeforeEach
    void setUp() {
        Farm farm = new Farm();
        farm.setId(1L);
        farm.setFarmName("Test farm");
        when(farms.findById(1L)).thenReturn(Optional.of(farm));

        FarmStateReading r1 = reading(1L, D1, 30.0);
        FarmStateReading r2 = reading(2L, D1.plusDays(5), 18.0);
        when(readings.findByFarmIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(r2, r1));

        AgriculturalAction weed = new AgriculturalAction();
        weed.setId(7L);
        weed.setActionName("Hand weed");
        weed.setCategory("Weed management");

        first = plan(10L, "SUPERSEDED", D1.plusDays(1), D1.plusDays(1).plusHours(1));
        ActionPlanItem item = new ActionPlanItem();
        item.setId(100L);
        item.setAgriculturalAction(weed);
        item.setSequenceOrder(1);
        item.setValidationMessage("RECOMMENDED: All checks passed.");
        first.addItem(item);

        second = plan(11L, "ACCEPTED", D1.plusDays(6), D1.plusDays(6).plusHours(2));
        rejected = plan(12L, "REJECTED", D1.plusDays(7), D1.plusDays(7).plusHours(1));
        when(plans.findByFarmIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(rejected, second, first));

        ActionExecution done = new ActionExecution();
        done.setActionPlanItem(item);
        done.setStatus(ActionExecution.DONE);
        done.setExecutedOn(LocalDate.of(2026, 11, 3));
        done.setRecordedAt(LocalDateTime.of(2026, 11, 4, 8, 0));
        done.setNote("Weeded the east side");
        when(executions.findByActionPlanItemActionPlanFarmId(1L)).thenReturn(List.of(done));
    }

    @Test
    void buildsOneTimelineNewestFirstFromAllTables() {
        HistoryResponse h = service.getFarmHistory(1L, null, null, null);

        List<String> types = h.events().stream().map(HistoryEvent::type).toList();
        assertEquals(List.of(
                "PLAN_REJECTED", "PLAN_CREATED",          // plan 12
                "PLAN_SUPERSEDED", "PLAN_ACCEPTED",       // plan 10 replaced as plan 11 is accepted
                "PLAN_CREATED",                           // plan 11
                "STATE_READING",                          // reading 2
                "ACTION_DONE",                            // reported for 3 Nov
                "PLAN_ACCEPTED", "PLAN_CREATED",          // plan 10
                "STATE_READING"), types);                 // reading 1
        assertEquals(10, h.total());
        assertEquals(2, h.countsByType().get("PLAN_ACCEPTED"));
        assertTrue(h.notes().get(0).contains("not stored"));
    }

    @Test
    void supersededTimeIsTheNextAcceptanceAndMarkedInferred() {
        HistoryEvent sup = service.getFarmHistory(1L, List.of("PLAN_SUPERSEDED"), null, null)
                .events().get(0);
        assertEquals(second.getReviewedAt(), sup.at());
        assertTrue(sup.timeInferred());
        assertTrue(sup.detail().contains("#11"));
    }

    @Test
    void readingShowsWhatChangedSinceThePreviousOne() {
        List<HistoryEvent> r = service.getFarmHistory(1L, List.of("STATE"), null, null).events();
        assertEquals(2, r.size());
        assertEquals(1, r.get(0).changes().size());
        assertEquals("soilMoisture", r.get(0).changes().get(0).field());
        assertEquals(-12.0, r.get(0).changes().get(0).difference());
        assertTrue(r.get(1).detail().startsWith("First recorded farm state"));
    }

    @Test
    void actionEventCarriesNoteAndReportedDate() {
        List<HistoryEvent> a = service.getFarmHistory(1L, List.of("ACTION"), null, null).events();
        assertEquals(1, a.size());
        assertEquals("Weeded the east side", a.get(0).note());
        assertEquals(LocalDate.of(2026, 11, 3).atStartOfDay(), a.get(0).at());
        assertEquals(10L, a.get(0).planId());
    }

    @Test
    void filtersByGroupsAndDateRange() {
        HistoryResponse h = service.getFarmHistory(1L, List.of("plan,ACTION_DONE"),
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));
        List<String> types = h.events().stream().map(HistoryEvent::type).toList();
        assertEquals(List.of("ACTION_DONE", "PLAN_ACCEPTED", "PLAN_CREATED"), types);
        assertEquals(1, h.countsByType().get("ACTION_DONE"));
        assertEquals(0, h.countsByType().get("STATE_READING"));
    }

    @Test
    void rejectsUnknownTypeAndReversedRange() {
        assertThrows(InvalidRequestException.class,
                () -> service.getFarmHistory(1L, List.of("PREDICTION"), null, null));
        assertThrows(InvalidRequestException.class, () -> service.getFarmHistory(1L, null,
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1)));
    }

    @Test
    void missingFarmIsNotFound() {
        when(farms.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getFarmHistory(99L, null, null, null));
    }

    private static FarmStateReading reading(Long id, LocalDateTime at, Double moisture) {
        FarmStateReading r = new FarmStateReading();
        r.setId(id);
        r.setRecordedAt(at);
        r.setCrop("Rice");
        r.setGrowthStage("Vegetative");
        r.setSoilMoisture(moisture);
        return r;
    }

    private static ActionPlan plan(Long id, String status, LocalDateTime created, LocalDateTime reviewed) {
        ActionPlan p = new ActionPlan();
        p.setId(id);
        p.setStatus(status);
        p.setCreatedAt(created);
        p.setReviewedAt(reviewed);
        return p;
    }
}
