package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

class FarmStateServiceImplTest {

    private final FarmStateRepository states = mock(FarmStateRepository.class);
    private final FarmRepository farms = mock(FarmRepository.class);
    private final FarmStateServiceImpl service = new FarmStateServiceImpl(states, farms);

    private static FarmState withFarm(long farmId) {
        Farm farm = new Farm();
        farm.setId(farmId);
        FarmState s = new FarmState();
        s.setFarm(farm);
        return s;
    }

    @Test
    void addRejectsInvalidValuesBeforeTouchingTheDatabase() {
        FarmState s = withFarm(1L);
        s.setHumidity(150.0);

        assertThrows(InvalidRequestException.class, () -> service.addFarmState(s));

        verify(farms, never()).findById(any());
        verify(states, never()).save(any());
    }

    @Test
    void updateRejectsInvalidValuesAndKeepsStoredState() {
        FarmState s = new FarmState();
        s.setSoilPh(15.0);

        assertThrows(InvalidRequestException.class, () -> service.updateFarmState(1L, s));

        verify(states, never()).save(any());
    }

    @Test
    void updateSavesValidValues() {
        FarmState existing = withFarm(1L);
        existing.setId(1L);
        when(states.findById(1L)).thenReturn(Optional.of(existing));
        when(states.save(any(FarmState.class))).thenAnswer(i -> i.getArgument(0));

        FarmState change = new FarmState();
        change.setSoilPh(7.0);
        change.setLabourAvailable(5);

        FarmState saved = service.updateFarmState(1L, change);

        assertEquals(7.0, saved.getSoilPh());
        assertEquals(5, saved.getLabourAvailable());
    }
}
