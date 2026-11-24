package com.agrisense.backend.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.agrisense.backend.exception.ApiExceptionHandler;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.model.FarmState;

class FarmStateValidatorTest {

    private static FarmState valid() {
        FarmState s = new FarmState();
        s.setCrop("Rice");
        s.setGrowthStage("Vegetative");
        s.setSoilMoisture(30.0);
        s.setSoilPh(6.5);
        s.setNitrogen(80.0);
        s.setPhosphorus(40.0);
        s.setPotassium(40.0);
        s.setTemperature(30.0);
        s.setHumidity(70.0);
        s.setRainfall(100.0);
        s.setWaterAvailable(500.0);
        s.setLabourAvailable(3);
        s.setBudgetAvailable(5000.0);
        s.setCropHealth("Healthy");
        s.setDiseaseStatus("No known disease");
        return s;
    }

    @Test
    void acceptsSensibleValues() {
        assertDoesNotThrow(() -> FarmStateValidator.validate(valid()));
    }

    @Test
    void acceptsEmptyStateBecauseEveryFieldIsOptional() {
        assertDoesNotThrow(() -> FarmStateValidator.validate(new FarmState()));
    }

    @Test
    void acceptsBoundaryValues() {
        FarmState s = valid();
        s.setSoilPh(0.0);
        s.setHumidity(100.0);
        s.setNitrogen(0.0);
        s.setWaterAvailable(0.0);
        s.setLabourAvailable(0);
        s.setBudgetAvailable(0.0);
        assertDoesNotThrow(() -> FarmStateValidator.validate(s));
        s.setSoilPh(14.0);
        s.setHumidity(0.0);
        assertDoesNotThrow(() -> FarmStateValidator.validate(s));
    }

    @Test
    void rejectsNullBody() {
        assertThrows(InvalidRequestException.class, () -> FarmStateValidator.validate(null));
    }

    @Test
    void rejectsPhOutsideZeroToFourteen() {
        FarmState s = valid();
        s.setSoilPh(14.5);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertEquals("Soil pH must be between 0 and 14", e.getFieldErrors().get("soilPh"));

        s.setSoilPh(-0.1);
        assertThrows(InvalidRequestException.class, () -> FarmStateValidator.validate(s));
    }

    @Test
    void rejectsNegativeNutrients() {
        FarmState s = valid();
        s.setNitrogen(-1.0);
        s.setPhosphorus(-2.0);
        s.setPotassium(-3.0);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertEquals(3, e.getFieldErrors().size());
        assertTrue(e.getFieldErrors().containsKey("nitrogen"));
        assertTrue(e.getFieldErrors().containsKey("phosphorus"));
        assertTrue(e.getFieldErrors().containsKey("potassium"));
    }

    @Test
    void rejectsHumidityAboveHundred() {
        FarmState s = valid();
        s.setHumidity(101.0);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertEquals("Humidity must be between 0 and 100", e.getFieldErrors().get("humidity"));
    }

    @Test
    void rejectsNegativeResources() {
        FarmState s = valid();
        s.setWaterAvailable(-5.0);
        s.setLabourAvailable(-1);
        s.setBudgetAvailable(-100.0);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertEquals(Map.of(
                "waterAvailable", "Water available cannot be negative",
                "labourAvailable", "Labour available cannot be negative",
                "budgetAvailable", "Budget available cannot be negative"),
                e.getFieldErrors());
        assertTrue(e.getMessage().startsWith("Invalid farm state: "));
    }

    @Test
    void rejectsSoilMoistureTemperatureAndRainfallOutOfRange() {
        FarmState s = valid();
        s.setSoilMoisture(120.0);
        s.setTemperature(75.0);
        s.setRainfall(-1.0);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertEquals(3, e.getFieldErrors().size());
    }

    @Test
    void rejectsNaNAndOverlongText() {
        FarmState s = valid();
        s.setSoilPh(Double.NaN);
        s.setCrop("x".repeat(256));
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));
        assertTrue(e.getFieldErrors().containsKey("soilPh"));
        assertTrue(e.getFieldErrors().containsKey("crop"));
    }

    @Test
    void handlerTurnsValidationErrorIntoBadRequestWithFieldErrors() {
        FarmState s = valid();
        s.setSoilPh(20.0);
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> FarmStateValidator.validate(s));

        ResponseEntity<Map<String, Object>> response = new ApiExceptionHandler().badRequest(e);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(false, response.getBody().get("success"));
        assertEquals("Invalid farm state: Soil pH must be between 0 and 14",
                response.getBody().get("message"));
        assertEquals(Map.of("soilPh", "Soil pH must be between 0 and 14"),
                response.getBody().get("errors"));
    }
}
