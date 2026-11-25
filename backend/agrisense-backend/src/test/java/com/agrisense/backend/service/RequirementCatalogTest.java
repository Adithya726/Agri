package com.agrisense.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.agrisense.backend.dto.ConstraintResult;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.StepRequirements;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RequirementCatalogTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final RequirementCatalog catalog = new RequirementCatalog(MAPPER);

    private static AgriculturalAction action(String crop, String stage, String name) {
        AgriculturalAction a = new AgriculturalAction();
        a.setId(1L);
        a.setApplicableCrop(crop);
        a.setApplicableGrowthStage(stage);
        a.setActionName(name);
        a.setActive(Boolean.TRUE);
        a.setEvidenceStatus("REFERENCE_SUPPORTED");
        return a;
    }

    private static JsonNode json(String file) throws IOException {
        return MAPPER.readTree(new ClassPathResource(file).getInputStream());
    }

    @Test
    void workedFertilizerCheckMatchesTheBrief() {
        // Rice Kharif 80:60:40 kg/ha -> DAP 52.8 + urea 49.7 + MOP 27.0 kg per acre, about Rs 2,642.
        Map<String, double[]> perAcre = catalog.fertilizerPerAcre(80, 60, 40);

        assertEquals(52.8, perAcre.get("DAP")[0], 0.05);
        assertEquals(49.7, perAcre.get("UREA")[0], 0.05);
        assertEquals(27.0, perAcre.get("MOP")[0], 0.05);
        double total = perAcre.values().stream().mapToDouble(v -> v[1]).sum();
        assertEquals(2642, total, 5);
    }

    @Test
    void everyAngrauActionHasRequirementsAndEveryFigureHasASource() throws IOException {
        JsonNode actions = json(AgriculturalActionSeeder.DATA_FILE).path("actions");
        int count = 0;
        for (JsonNode a : actions) {
            AgriculturalAction act = action(a.path("crop").asString(), a.path("stage").asString(),
                    a.path("name").asString());
            StepRequirements r = catalog.requirementsFor(act, 2.0, "Kharif").orElseThrow();
            count++;

            assertNotNull(r.timing().text(), act.getActionName());
            assertNotNull(r.timing().source(), act.getActionName());
            for (StepRequirements.Input in : r.inputs()) {
                assertTrue(in.doseSource().startsWith("http"), in.product());
                assertTrue(in.priceSource().startsWith("http"), in.product());
                assertNotNull(in.priceDate(), in.product());
                assertTrue(in.costMin() > 0, in.product());
                assertFalse(catalog.isBanned(in.name()), in.name());
            }
            if (r.labour().sourced()) {
                for (StepRequirements.LabourPart p : r.labour().parts()) {
                    assertTrue(p.source().startsWith("http"));
                    assertTrue(p.wageSource().startsWith("http"));
                }
            } else {
                assertNotNull(r.labour().notSourced());
            }
            if (r.water().sourced() && r.water().includedIn() == null) {
                assertTrue(r.water().source().startsWith("http"), act.getActionName());
            }
        }
        assertEquals(24, count);
    }

    @Test
    void everyPriceWageAndLabourFigureHasAUrl() throws IOException {
        JsonNode prices = json(RequirementCatalog.PRICES_FILE);
        for (JsonNode p : prices.path("products")) {
            assertTrue(p.path("source").asString().startsWith("http"), p.path("name").asString());
        }
        for (JsonNode l : prices.path("labour")) {
            assertTrue(l.path("source").asString().startsWith("http"));
        }
        for (JsonNode w : prices.path("water")) {
            assertTrue(w.path("source").asString().startsWith("http"));
        }
    }

    @Test
    void amountsScaleWithFarmArea() {
        AgriculturalAction blast = action("rice", "Flowering", "Spray for neck blast before panicle emergence");

        StepRequirements one = catalog.requirementsFor(blast, 1.0, "Kharif").orElseThrow();
        StepRequirements three = catalog.requirementsFor(blast, 3.0, "Kharif").orElseThrow();

        // Tricyclazole 0.6 g/l x 200 l/acre = 120 g per acre.
        StepRequirements.Input tricyclazole = one.inputs().get(0);
        assertEquals("TRICYCLAZOLE", tricyclazole.product());
        assertEquals(120.0, tricyclazole.quantityMin());
        assertEquals(360.0, three.inputs().get(0).quantityMin());
        // 120 g at Rs 264 per 100 g.
        assertEquals(317.0, tricyclazole.costMin());
        // Spray water: 200 l per acre = 0.2 m3.
        assertEquals(0.2, one.water().m3Min());
        assertEquals(0.6, three.water().m3Min());
        // Knapsack spray: 6 person-days per ha = 2.43 per acre, men's plant-protection wage.
        assertEquals(2.43, one.labour().personDaysMax());
        assertEquals((double) Math.round(2.43 * 896.83), one.labour().costMax());
    }

    @Test
    void nitrogenSplitFollowsTheSeason() {
        AgriculturalAction topDress = action("rice", "Vegetative", "Top-dress nitrogen at active tillering");

        StepRequirements kharif = catalog.requirementsFor(topDress, 1.0, "Kharif").orElseThrow();
        StepRequirements rabi = catalog.requirementsFor(topDress, 1.0, "Rabi").orElseThrow();

        // (80/3 kg N/ha) / 0.46 / 2.471 = 23.46 kg urea per acre.
        assertEquals(23.46, kharif.inputs().get(0).quantityMin(), 0.01);
        // (120/3 kg N/ha) / 0.46 / 2.471 = 35.19 kg urea per acre.
        assertEquals(35.19, rabi.inputs().get(0).quantityMin(), 0.01);
        assertFalse(kharif.labour().sourced());
    }

    @Test
    void riceWaterUsesTheTnauStageFigures() {
        AgriculturalAction water = action("rice", "Flowering",
                "Maintain 5 cm water from panicle initiation; avoid stress at flowering");

        StepRequirements r = catalog.requirementsFor(water, 1.0, "Kharif").orElseThrow();

        // 417 + 125 mm = 54.2 cm x 40.47 m3 per cm per acre.
        assertEquals(2193.5, r.water().m3Min(), 0.1);
    }

    @Test
    void choiceStepsCostFromCheapestToDearestOption() {
        AgriculturalAction herbicide = action("rice", "Seedling",
                "Apply pre-emergence herbicide within 3-5 days of transplanting");

        StepRequirements r = catalog.requirementsFor(herbicide, 1.0, "Kharif").orElseThrow();

        // Anilofos 0.5 l = Rs 257; oxadiargyl 40 g = Rs 400; butachlor 1.25 l = Rs 863.
        assertEquals(257.0, r.cost().min());
        assertEquals(863.0, r.cost().max());
        assertFalse(r.cost().includesLabour());
        assertTrue(r.notSourced().stream().anyMatch(s -> s.contains("herbicide application")));
    }

    @Test
    void bannedChemicalsOnlyAppearAsSafetyNotes() {
        AgriculturalAction gallMidge = action("rice", "Seedling", "Control gall midge with granules at 15 DAT");

        StepRequirements r = catalog.requirementsFor(gallMidge, 1.0, "Kharif").orElseThrow();

        assertEquals(1, r.inputs().size());
        assertEquals("CARBOFURAN_3G", r.inputs().get(0).product());
        assertEquals(1, r.safetyNotes().size());
        assertTrue(r.safetyNotes().get(0).toLowerCase().contains("phorate"));
    }

    @Test
    void catalogRefusesABannedProduct() throws IOException {
        JsonNode prices = json(RequirementCatalog.PRICES_FILE);
        ((tools.jackson.databind.node.ObjectNode) prices.path("products").path("CHLORPYRIPHOS"))
                .put("name", "Monocrotophos 36 SL");
        JsonNode requirements = json(RequirementCatalog.REQUIREMENTS_FILE);

        assertThrows(IllegalStateException.class, () -> new RequirementCatalog(prices, requirements));
    }

    @Test
    void unknownActionHasNoRequirements() {
        assertTrue(catalog.requirementsFor(action("rice", "Seedling", "Admin step"), 1.0, "Kharif").isEmpty());
    }

    // ------------------------------------------------------------------
    // Constraint checks use the computed requirements.

    private final ConstraintValidationServiceImpl validation = new ConstraintValidationServiceImpl(
            mock(FarmRepository.class), mock(FarmStateRepository.class),
            mock(AgriculturalActionRepository.class), catalog);

    private static FarmState state(Double area, Double water, Integer workers, Double budget) {
        Farm farm = new Farm();
        farm.setId(1L);
        farm.setArea(area);
        FarmState s = new FarmState();
        s.setFarm(farm);
        s.setCrop("rice");
        s.setGrowthStage("Flowering");
        s.setWaterAvailable(water);
        s.setLabourAvailable(workers);
        s.setBudgetAvailable(budget);
        return s;
    }

    private static String status(ConstraintValidationResponse r, String category) {
        return r.getConstraints().stream().filter(c -> category.equals(c.getCategory()))
                .map(ConstraintResult::getStatus).findFirst().orElseThrow();
    }

    @Test
    void sprayIsFeasibleWithEnoughWaterWorkersAndBudget() {
        AgriculturalAction blast = action("rice", "Flowering", "Spray for neck blast before panicle emergence");

        ConstraintValidationResponse r = validation.validateAction(1L, state(2.0, 10.0, 2, 10000.0), blast);

        assertEquals("FEASIBLE", r.getOverallStatus());
        assertTrue(r.getConstraints().stream().anyMatch(c -> c.getMessage().contains("days with your 2 workers")));
    }

    @Test
    void lowBudgetMakesTheStepInfeasible() {
        AgriculturalAction blast = action("rice", "Flowering", "Spray for neck blast before panicle emergence");

        ConstraintValidationResponse r = validation.validateAction(1L, state(2.0, 10.0, 2, 100.0), blast);

        assertEquals("INFEASIBLE", r.getOverallStatus());
        assertEquals("INFEASIBLE", status(r, "BUDGET"));
    }

    @Test
    void lowWaterMakesIrrigationInfeasible() {
        AgriculturalAction water = action("rice", "Flowering",
                "Maintain 5 cm water from panicle initiation; avoid stress at flowering");

        ConstraintValidationResponse r = validation.validateAction(1L, state(1.0, 400.0, 2, 1000.0), water);

        assertEquals("INFEASIBLE", status(r, "WATER"));
        assertEquals("FIELD_GUIDE", status(r, "LABOUR"));
    }

    @Test
    void missingAreaAsksForIt() {
        AgriculturalAction blast = action("rice", "Flowering", "Spray for neck blast before panicle emergence");

        ConstraintValidationResponse r = validation.validateAction(1L, state(null, 10.0, 2, 10000.0), blast);

        assertEquals("NEEDS_INPUT", r.getOverallStatus());
        assertTrue(r.getConstraints().get(2).getMessage().contains("area"));
    }

    @Test
    void noWorkersIsInfeasibleWhenLabourIsSourced() {
        AgriculturalAction harvest = action("rice", "Harvest",
                "Harvest at 80% grain maturity and dry to below 13% moisture");
        FarmState s = state(1.0, 0.0, 0, 100000.0);
        s.setGrowthStage("Harvest");

        ConstraintValidationResponse r = validation.validateAction(1L, s, harvest);

        assertEquals("INFEASIBLE", status(r, "LABOUR"));
        assertEquals("FEASIBLE", status(r, "WATER"));
        assertTrue(r.getConstraints().stream().filter(c -> "WATER".equals(c.getCategory()))
                .findFirst().orElseThrow().getMessage().contains("no extra water"));
    }
}
