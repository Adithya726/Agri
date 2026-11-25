package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Optional;
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

    private static AgriculturalAction existing(String name, String crop, String stage, String description) {
        AgriculturalAction a = new AgriculturalAction();
        a.setId(7L);
        a.setActionName(name);
        a.setApplicableCrop(crop);
        a.setApplicableGrowthStage(stage);
        a.setDescription(description);
        a.setEvidenceStatus(AgriculturalActionSeeder.EVIDENCE_STATUS);
        a.setActive(Boolean.FALSE);
        return a;
    }

    private void stubFind(String name, String crop, String stage, AgriculturalAction row) {
        when(repository.findFirstByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                eq(name), eq(crop), eq(stage))).thenReturn(Optional.of(row));
    }

    @Test
    void seedInsertsOnlyMissingActions() throws IOException {
        List<AgriculturalAction> fileActions = seeder.read(dataFile());
        AgriculturalAction weed = fileActions.stream()
                .filter(a -> a.getActionName().startsWith("Hand weed")).findFirst().orElseThrow();
        AgriculturalAction row = existing(weed.getActionName(), "rice", "Vegetative", weed.getDescription());
        row.setCategory(weed.getCategory());
        row.setSourceReference(weed.getSourceReference());
        stubFind(weed.getActionName(), "rice", "Vegetative", row);

        int added = seeder.seed(dataFile());

        assertEquals(fileActions.size() - 1, added);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgriculturalAction>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertTrue(saved.getValue().stream()
                .noneMatch(a -> a.getActionName().startsWith("Hand weed")));
    }

    @Test
    void seedUpdatesChangedSourcedRowsButKeepsActiveFlagAndId() throws IOException {
        String name = "Control gall midge with granules at 15 DAT";
        AgriculturalAction row = existing(name, "rice", "Seedling",
                "Apply phorate 10G at 12.5 kg/ha or carbofuran 3G at 25 kg/ha.");
        stubFind(name, "rice", "Seedling", row);

        AgriculturalActionSeeder.Result result = seeder.seedOrUpdate(dataFile());

        assertEquals(1, result.updated());
        assertTrue(row.getDescription().startsWith("Apply carbofuran 3G"));
        assertEquals(7L, row.getId());
        assertFalse(row.getActive());
    }

    @Test
    void seedLeavesAdminAddedRowsAlone() throws IOException {
        String name = "Control gall midge with granules at 15 DAT";
        AgriculturalAction row = existing(name, "rice", "Seedling", "Admin wording");
        row.setEvidenceStatus("EXPERT_REVIEWED");
        stubFind(name, "rice", "Seedling", row);

        AgriculturalActionSeeder.Result result = seeder.seedOrUpdate(dataFile());

        assertEquals(0, result.updated());
        assertEquals("Admin wording", row.getDescription());
    }

    @Test
    void seedSavesNothingWhenAllActionsAreCurrent() throws IOException {
        for (AgriculturalAction a : seeder.read(dataFile())) {
            AgriculturalAction row = existing(a.getActionName(), a.getApplicableCrop(),
                    a.getApplicableGrowthStage(), a.getDescription());
            row.setCategory(a.getCategory());
            row.setSourceReference(a.getSourceReference());
            stubFind(a.getActionName(), a.getApplicableCrop(), a.getApplicableGrowthStage(), row);
        }

        assertEquals(0, seeder.seed(dataFile()));
        verify(repository, never()).saveAll(any());
    }

    @Test
    void noStepRecommendsABannedChemical() throws IOException {
        for (AgriculturalAction a : seeder.read(dataFile())) {
            String d = a.getDescription().toLowerCase(java.util.Locale.ROOT);
            for (String banned : List.of("phorate", "phosphamidon", "monocrotophos")) {
                int at = d.indexOf(banned);
                if (at >= 0) {
                    assertTrue(d.substring(at).contains("do not use"), a.getActionName());
                }
            }
        }
    }

    @Test
    void actionWithoutSourceIsRefused() {
        String json = "{\"sources\":{},\"actions\":[{\"source\":\"X\",\"crop\":\"rice\","
                + "\"stage\":\"Seedling\",\"category\":\"c\",\"name\":\"n\",\"description\":\"d\"}]}";
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> seeder.read(in));
    }
}
