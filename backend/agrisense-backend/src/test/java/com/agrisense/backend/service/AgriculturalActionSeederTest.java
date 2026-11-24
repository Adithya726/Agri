package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;

import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.repository.AgriculturalActionRepository;

import tools.jackson.databind.json.JsonMapper;

class AgriculturalActionSeederTest {

    // The growth stages the farm state form offers.
    private static final Set<String> STAGES = Set.of(
            "Seedling", "Vegetative", "Flowering", "Fruiting", "Maturity", "Harvest");

    private final AgriculturalActionRepository repository = mock(AgriculturalActionRepository.class);
    private final AgriculturalActionSeeder seeder =
            new AgriculturalActionSeeder(repository, JsonMapper.builder().build(), true);

    private static InputStream dataFile() throws IOException {
        return new ClassPathResource(AgriculturalActionSeeder.DATA_FILE).getInputStream();
    }

    @Test
    void everySourcedActionIsReferenceSupportedAndHasNoInventedResources() throws IOException {
        List<AgriculturalAction> actions = seeder.read(dataFile());

        assertFalse(actions.isEmpty());
        for (AgriculturalAction a : actions) {
            assertTrue(ActionPrioritizer.isReferenceSupported(a), a.getActionName());
            assertTrue(a.getSourceReference().contains("ANGRAU"), a.getActionName());
            assertTrue(a.getSourceReference().length() <= 255, a.getActionName());
            assertTrue(a.getDescription().length() <= 1000, a.getActionName());
            assertTrue(STAGES.contains(a.getApplicableGrowthStage()), a.getApplicableGrowthStage());
            assertTrue(Set.of("rice", "maize").contains(a.getApplicableCrop()));
            assertTrue(a.getActive());
            assertNull(a.getRequiredWater());
            assertNull(a.getRequiredLabour());
            assertNull(a.getRequiredBudget());
        }
    }

    @Test
    void seedInsertsOnlyMissingActions() throws IOException {
        when(repository.existsByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                anyString(), anyString(), anyString())).thenReturn(false);
        when(repository.existsByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                eq("Hand weed at 20 and 40 days after transplanting"), eq("rice"), eq("Vegetative")))
                .thenReturn(true);

        int total = seeder.read(dataFile()).size();
        int added = seeder.seed(dataFile());

        assertEquals(total - 1, added);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgriculturalAction>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertTrue(saved.getValue().stream()
                .noneMatch(a -> a.getActionName().startsWith("Hand weed")));
    }

    @Test
    void seedSavesNothingWhenAllActionsExist() throws IOException {
        when(repository.existsByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                anyString(), anyString(), anyString())).thenReturn(true);

        assertEquals(0, seeder.seed(dataFile()));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void actionWithoutSourceIsRefused() {
        String json = "{\"sources\":{},\"actions\":[{\"source\":\"X\",\"crop\":\"rice\","
                + "\"stage\":\"Seedling\",\"category\":\"c\",\"name\":\"n\",\"description\":\"d\"}]}";
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> seeder.read(in));
    }
}
