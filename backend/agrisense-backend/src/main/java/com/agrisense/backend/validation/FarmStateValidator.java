package com.agrisense.backend.validation;

import java.util.LinkedHashMap;
import java.util.Map;

import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.FarmState;

/*
 * Range checks for farm state values entered by the farmer.
 *
 * Every field stays optional (the context, risk and prediction
 * services already report missing values as UNKNOWN), but a value
 * that is present must be physically sensible. All problems are
 * collected and reported together as one 400 response.
 */
public final class FarmStateValidator {

    public static final double MIN_PH = 0.0;
    public static final double MAX_PH = 14.0;
    public static final double MIN_TEMPERATURE = -50.0;
    public static final double MAX_TEMPERATURE = 60.0;
    public static final int MAX_TEXT_LENGTH = 255;

    private FarmStateValidator() {
    }

    public static void validate(FarmState state) {

        if (state == null) {
            throw new InvalidRequestException("Farm state values are required");
        }

        Map<String, String> errors = new LinkedHashMap<>();

        range(errors, "soilMoisture", "Soil moisture", state.getSoilMoisture(), 0.0, 100.0);
        range(errors, "soilPh", "Soil pH", state.getSoilPh(), MIN_PH, MAX_PH);

        nonNegative(errors, "nitrogen", "Nitrogen", state.getNitrogen());
        nonNegative(errors, "phosphorus", "Phosphorus", state.getPhosphorus());
        nonNegative(errors, "potassium", "Potassium", state.getPotassium());

        range(errors, "temperature", "Temperature", state.getTemperature(),
                MIN_TEMPERATURE, MAX_TEMPERATURE);
        range(errors, "humidity", "Humidity", state.getHumidity(), 0.0, 100.0);
        nonNegative(errors, "rainfall", "Rainfall", state.getRainfall());

        nonNegative(errors, "waterAvailable", "Water available", state.getWaterAvailable());
        if (state.getLabourAvailable() != null && state.getLabourAvailable() < 0) {
            errors.put("labourAvailable", "Labour available cannot be negative");
        }
        nonNegative(errors, "budgetAvailable", "Budget available", state.getBudgetAvailable());

        text(errors, "crop", "Crop", state.getCrop());
        text(errors, "growthStage", "Growth stage", state.getGrowthStage());
        text(errors, "cropHealth", "Crop health", state.getCropHealth());
        text(errors, "diseaseStatus", "Disease status", state.getDiseaseStatus());

        if (!errors.isEmpty()) {
            throw new InvalidRequestException(
                    "Invalid farm state: " + String.join("; ", errors.values()),
                    errors);
        }
    }

    private static void range(Map<String, String> errors, String field, String label,
                              Double value, double min, double max) {
        if (value == null) {
            return;
        }
        if (value.isNaN() || value.isInfinite() || value < min || value > max) {
            errors.put(field, label + " must be between " + format(min) + " and " + format(max));
        }
    }

    private static void nonNegative(Map<String, String> errors, String field, String label,
                                    Double value) {
        if (value == null) {
            return;
        }
        if (value.isNaN() || value.isInfinite() || value < 0) {
            errors.put(field, label + " cannot be negative");
        }
    }

    private static void text(Map<String, String> errors, String field, String label,
                             String value) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            errors.put(field, label + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
