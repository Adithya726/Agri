package com.agrisense.backend.service;

import java.util.ArrayList;

import java.util.List;

import org.springframework.stereotype.Service;

import com.agrisense.backend.dto.ContextInterpretationResponse;

import com.agrisense.backend.dto.ContextInterpretationResponse.ContextItem;

import com.agrisense.backend.exception.ResourceNotFoundException;

import com.agrisense.backend.model.Farm;

import com.agrisense.backend.model.FarmState;

import com.agrisense.backend.repository.FarmRepository;

import com.agrisense.backend.repository.FarmStateRepository;

@Service

public class ContextInterpretationServiceImpl

        implements ContextInterpretationService {

    private final FarmRepository farmRepository;

    private final FarmStateRepository farmStateRepository;

    public ContextInterpretationServiceImpl(

            FarmRepository farmRepository,

            FarmStateRepository farmStateRepository) {

        this.farmRepository = farmRepository;

        this.farmStateRepository = farmStateRepository;

    }

    @Override

    public ContextInterpretationResponse interpretFarmContext(

            Long farmId) {

        Farm farm = farmRepository.findById(farmId)

                .orElseThrow(() ->

                        new ResourceNotFoundException("Farm not found"));

        FarmState farmState = farmStateRepository

                .findByFarm(farm)

                .orElseThrow(() ->

                        new ResourceNotFoundException("Farm state not found"));

        return interpret(farm, farmState, farm.getCrop(), farm.getGrowthStage());

    }

    /*
     * Same rules on a farm state that is not saved (what-if analysis).
     * The crop and growth stage reported are the ones in that state.
     */
    @Override
    public ContextInterpretationResponse interpretFarmContext(
            Farm farm, FarmState farmState) {

        return interpret(farm, farmState, farmState.getCrop(), farmState.getGrowthStage());

    }

    private ContextInterpretationResponse interpret(
            Farm farm, FarmState farmState, String crop, String growthStage) {

        List<ContextItem> context = new ArrayList<>();

        // =========================

        // SOIL

        // =========================

        context.add(

                interpretSoilPh(farmState.getSoilPh())

        );

        context.add(

                interpretNitrogen(farmState.getNitrogen())

        );

        context.add(

                interpretPhosphorus(

                        farmState.getPhosphorus())

        );

        context.add(

                interpretPotassium(

                        farmState.getPotassium())

        );

        // =========================

        // WEATHER

        // =========================

        context.add(

                interpretTemperature(

                        farmState.getTemperature())

        );

        context.add(

                interpretHumidity(

                        farmState.getHumidity())

        );

        context.add(

                interpretRainfall(

                        farmState.getRainfall())

        );

        // =========================

        // WATER

        // =========================

        context.add(

                interpretSoilMoisture(

                        farmState.getSoilMoisture())

        );

        context.add(

                interpretWaterAvailability(

                        farmState.getWaterAvailable())

        );

        // =========================

        // RESOURCES

        // =========================

        context.add(

                interpretLabour(

                        farmState.getLabourAvailable())

        );

        context.add(

                interpretBudget(

                        farmState.getBudgetAvailable())

        );

        // =========================

        // CROP

        // =========================

        context.add(

                new ContextItem(

                        "CROP",

                        "Crop",

                        farmState.getCrop(),

                        "Current crop",

                        "INFO"

                )

        );

        context.add(

                new ContextItem(

                        "CROP",

                        "Growth Stage",

                        farmState.getGrowthStage(),

                        "Current crop growth stage",

                        "INFO"

                )

        );

        context.add(

                new ContextItem(

                        "CROP",

                        "Crop Health",

                        farmState.getCropHealth(),

                        "Current crop health status",

                        "INFO"

                )

        );

        // =========================

        // DISEASE

        // =========================

        context.add(

                new ContextItem(

                        "DISEASE",

                        "Disease Status",

                        farmState.getDiseaseStatus(),

                        interpretDisease(

                                farmState.getDiseaseStatus()),

                        getDiseaseSeverity(

                                farmState.getDiseaseStatus())

                )

        );

        return new ContextInterpretationResponse(

                true,

                "Farm context interpreted successfully",

                farm.getId(),

                crop,

                growthStage,

                context

        );

    }

















    // =========================================

    // SOIL INTERPRETATION

    // =========================================

    private ContextItem interpretSoilPh(

            Double value) {

        if (value == null) {

            return item(

                    "SOIL",

                    "Soil pH",

                    null,

                    "Soil pH data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value < 5.5) {

            return item(

                    "SOIL",

                    "Soil pH",

                    value,

                    "Soil is strongly acidic",

                    "HIGH"

            );

        }

        if (value < 6.0) {

            return item(

                    "SOIL",

                    "Soil pH",

                    value,

                    "Soil is moderately acidic",

                    "MEDIUM"

            );

        }

        if (value <= 7.5) {

            return item(

                    "SOIL",

                    "Soil pH",

                    value,

                    "Soil pH is generally suitable",

                    "LOW"

            );

        }

        if (value <= 8.0) {

            return item(

                    "SOIL",

                    "Soil pH",

                    value,

                    "Soil is moderately alkaline",

                    "MEDIUM"

            );

        }

        return item(

                "SOIL",

                "Soil pH",

                value,

                "Soil is strongly alkaline",

                "HIGH"

        );

    }

















    private ContextItem interpretNitrogen(

            Double value) {

        return nutrientInterpretation(

                "Nitrogen",

                value

        );

    }

















    private ContextItem interpretPhosphorus(

            Double value) {

        return nutrientInterpretation(

                "Phosphorus",

                value

        );

    }

















    private ContextItem interpretPotassium(

            Double value) {

        return nutrientInterpretation(

                "Potassium",

                value

        );

    }

















    private ContextItem nutrientInterpretation(

            String parameter,

            Double value) {

        if (value == null) {

            return item(

                    "SOIL",

                    parameter,

                    null,

                    parameter +

                            " data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value < 20) {

            return item(

                    "SOIL",

                    parameter,

                    value,

                    parameter +

                            " level appears low",

                    "MEDIUM"

            );

        }

        if (value > 120) {

            return item(

                    "SOIL",

                    parameter,

                    value,

                    parameter +

                            " level appears high",

                    "MEDIUM"

            );

        }

        return item(

                "SOIL",

                parameter,

                value,

                parameter +

                        " level appears within the reference range",

                "LOW"

        );

    }

















    // =========================================

    // WEATHER INTERPRETATION

    // =========================================

    private ContextItem interpretTemperature(

            Double value) {

        if (value == null) {

            return item(

                    "WEATHER",

                    "Temperature",

                    null,

                    "Temperature data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value < 10 || value > 40) {

            return item(

                    "WEATHER",

                    "Temperature",

                    value,

                    "Temperature is outside the broad reference range",

                    "HIGH"

            );

        }

        if (value < 18 || value > 35) {

            return item(

                    "WEATHER",

                    "Temperature",

                    value,

                    "Temperature may require attention",

                    "MEDIUM"

            );

        }

        return item(

                "WEATHER",

                "Temperature",

                value,

                "Temperature is within the broad reference range",

                "LOW"

        );

    }

















    private ContextItem interpretHumidity(

            Double value) {

        if (value == null) {

            return item(

                    "WEATHER",

                    "Humidity",

                    null,

                    "Humidity data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value > 85) {

            return item(

                    "WEATHER",

                    "Humidity",

                    value,

                    "Humidity is high",

                    "MEDIUM"

            );

        }

        if (value < 30) {

            return item(

                    "WEATHER",

                    "Humidity",

                    value,

                    "Humidity is low",

                    "MEDIUM"

            );

        }

        return item(

                "WEATHER",

                "Humidity",

                value,

                "Humidity is within the broad reference range",

                "LOW"

        );

    }

















    private ContextItem interpretRainfall(

            Double value) {

        if (value == null) {

            return item(

                    "WEATHER",

                    "Rainfall",

                    null,

                    "Rainfall data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value > 200) {

            return item(

                    "WEATHER",

                    "Rainfall",

                    value,

                    "Rainfall is high",

                    "MEDIUM"

            );

        }

        if (value < 20) {

            return item(

                    "WEATHER",

                    "Rainfall",

                    value,

                    "Rainfall is low",

                    "MEDIUM"

            );

        }

        return item(

                "WEATHER",

                "Rainfall",

                value,

                "Rainfall is within the broad reference range",

                "LOW"

        );

    }

















    // =========================================

    // WATER

    // =========================================

    private ContextItem interpretSoilMoisture(

            Double value) {

        if (value == null) {

            return item(

                    "WATER",

                    "Soil Moisture",

                    null,

                    "Soil moisture data unavailable",

                    "NEEDS_DATA"

            );

        }

        if (value < 20) {

            return item(

                    "WATER",

                    "Soil Moisture",

                    value,

                    "Soil moisture is low",

                    "HIGH"

            );

        }

        if (value > 80) {

            return item(

                    "WATER",

                    "Soil Moisture",

                    value,

                    "Soil moisture is high",

                    "MEDIUM"

            );

        }

        return item(

                "WATER",

                "Soil Moisture",

                value,

                "Soil moisture is within the broad reference range",

                "LOW"

        );

    }

















    private ContextItem interpretWaterAvailability(

            Number value) {

        if (value == null) {

            return item(

                    "WATER",

                    "Water Availability",

                    null,

                    "Water availability data unavailable",

                    "NEEDS_DATA"

            );

        }









        double numericValue = value.doubleValue();

        if (numericValue <= 0) {

            return item(

                    "WATER",

                    "Water Availability",

                    value,

                    "Water is unavailable",

                    "HIGH"

            );

        }

        return item(

                "WATER",

                "Water Availability",

                value,

                "Water is available",

                "LOW"

        );

    }

















    // =========================================

    // RESOURCES

    // =========================================

    private ContextItem interpretLabour(

            Number value) {

        if (value == null) {

            return item(

                    "RESOURCES",

                    "Labour Availability",

                    null,

                    "Labour availability data unavailable",

                    "NEEDS_DATA"

            );

        }









        double numericValue = value.doubleValue();

        if (numericValue <= 0) {

            return item(

                    "RESOURCES",

                    "Labour Availability",

                    value,

                    "Labour is unavailable",

                    "HIGH"

            );

        }

        return item(

                "RESOURCES",

                "Labour Availability",

                value,

                "Labour is available",

                "LOW"

        );

    }

















    private ContextItem interpretBudget(

            Number value) {

        if (value == null) {

            return item(

                    "RESOURCES",

                    "Budget Availability",

                    null,

                    "Budget availability data unavailable",

                    "NEEDS_DATA"

            );

        }









        double numericValue = value.doubleValue();

        if (numericValue <= 0) {

            return item(

                    "RESOURCES",

                    "Budget Availability",

                    value,

                    "Budget is unavailable",

                    "HIGH"

            );

        }

        return item(

                "RESOURCES",

                "Budget Availability",

                value,

                "Budget is available",

                "LOW"

        );

    }

















    // =========================================

    // DISEASE

    // =========================================

    private String interpretDisease(
            String diseaseStatus) {

        if (diseaseStatus == null ||
                diseaseStatus.isBlank()) {

            return "Disease status unavailable";
        }

        String status =
                diseaseStatus.trim().toLowerCase();

        if ((status.contains("unknown") || status.contains("not checked"))) {
            return "Disease status unavailable";
        }

        if (status.contains("healthy") ||
                status.contains("no known disease") ||
                status.contains("no disease") ||
                status.equals("none")) {

            return "No disease condition reported";
        }

        if (status.contains("suspected")) {
            return "Disease condition is suspected";
        }

        if (status.contains("confirmed")) {
            return "Disease condition is confirmed";
        }

        return "Disease condition reported";
    }

    private String getDiseaseSeverity(
            String diseaseStatus) {

        if (diseaseStatus == null ||
                diseaseStatus.isBlank()) {

            return "NEEDS_DATA";
        }

        String status =
                diseaseStatus.trim().toLowerCase();

        if ((status.contains("unknown") || status.contains("not checked"))) {
            return "NEEDS_DATA";
        }

        if (status.contains("healthy") ||
                status.contains("no known disease") ||
                status.contains("no disease") ||
                status.equals("none")) {

            return "LOW";
        }

        if (status.contains("suspected")) {
            return "MEDIUM";
        }

        if (status.contains("confirmed")) {
            return "HIGH";
        }

        return "HIGH";
    }

    // =========================================
    // COMMON BUILDER

    // =========================================

    private ContextItem item(

            String category,

            String parameter,

            Object value,

            String interpretation,

            String severity) {

        return new ContextItem(

                category,

                parameter,

                value == null

                        ? null

                        : String.valueOf(value),

                interpretation,

                severity

        );

    }

}