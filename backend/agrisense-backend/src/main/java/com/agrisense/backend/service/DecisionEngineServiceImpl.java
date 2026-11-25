package com.agrisense.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.agrisense.backend.dto.ConstraintValidationRequest;
import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.ContextInterpretationResponse;
import com.agrisense.backend.dto.ContextInterpretationResponse.ContextItem;
import com.agrisense.backend.dto.CropRecommendationResponse;
import com.agrisense.backend.dto.DecisionResponse;
import com.agrisense.backend.dto.DecisionResponse.ContextSummary;
import com.agrisense.backend.dto.DecisionResponse.CropSignal;
import com.agrisense.backend.dto.DecisionResponse.MlSignals;
import com.agrisense.backend.dto.DecisionResponse.YieldSignal;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.YieldPredictionResponse;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;

/*
 * Decision engine: combines the existing context interpretation, risk
 * analysis, constraint validation and ML services into one ranked,
 * sequenced list of agricultural actions for a farm.
 *
 * Each action is validated by the existing ConstraintValidationService;
 * this class does not repeat those checks. ML results are reported as
 * returned by the ML services, or as UNAVAILABLE with the reason.
 */
@Service
public class DecisionEngineServiceImpl implements DecisionEngineService {

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final AgriculturalActionRepository agriculturalActionRepository;
    private final ContextInterpretationService contextInterpretationService;
    private final RiskAnalysisService riskAnalysisService;
    private final ConstraintValidationService constraintValidationService;
    private final CropRecommendationService cropRecommendationService;
    private final YieldPredictionService yieldPredictionService;
    private final ActionPrioritizer actionPrioritizer;

    public DecisionEngineServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            AgriculturalActionRepository agriculturalActionRepository,
            ContextInterpretationService contextInterpretationService,
            RiskAnalysisService riskAnalysisService,
            ConstraintValidationService constraintValidationService,
            CropRecommendationService cropRecommendationService,
            YieldPredictionService yieldPredictionService,
            ActionPrioritizer actionPrioritizer) {

        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.agriculturalActionRepository = agriculturalActionRepository;
        this.contextInterpretationService = contextInterpretationService;
        this.riskAnalysisService = riskAnalysisService;
        this.constraintValidationService = constraintValidationService;
        this.cropRecommendationService = cropRecommendationService;
        this.yieldPredictionService = yieldPredictionService;
        this.actionPrioritizer = actionPrioritizer;
    }

    @Override
    public DecisionResponse decide(Long farmId) {

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        FarmState farmState = farmStateRepository.findByFarm(farm)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Farm state not found. Update the farm state first."));

        ContextInterpretationResponse context =
                contextInterpretationService.interpretFarmContext(farmId);
        RiskAnalysisResponse risk = riskAnalysisService.analyzeFarmRisks(farmId);

        List<String> notes = new ArrayList<>();

        // Evaluate active actions for the farm's current crop (or with no
        // crop set, so the validator can explain what is missing).
        String crop = trimToNull(farmState.getCrop());
        List<AgriculturalAction> active = agriculturalActionRepository.findByActiveTrue();
        List<ActionPrioritizer.Candidate> candidates = new ArrayList<>();
        int otherCrops = 0;

        for (AgriculturalAction action : active) {
            String actionCrop = trimToNull(action.getApplicableCrop());
            if (crop != null && actionCrop != null && !actionCrop.equalsIgnoreCase(crop)) {
                otherCrops++;
                continue;
            }
            ConstraintValidationRequest request = new ConstraintValidationRequest();
            request.setFarmId(farmId);
            request.setActionId(action.getId());
            ConstraintValidationResponse validation =
                    constraintValidationService.validateAction(request);
            candidates.add(new ActionPrioritizer.Candidate(action, validation));
        }

        ActionPrioritizer.Result result =
                actionPrioritizer.prioritize(candidates, risk, farmState);

        if (active.isEmpty()) {
            notes.add("No active agricultural actions exist in the knowledge base, so there is nothing to rank.");
        } else if (candidates.isEmpty()) {
            notes.add("No active agricultural action applies to " + crop + ".");
        }
        if (otherCrops > 0) {
            notes.add(otherCrops + " active action(s) for other crops were not evaluated.");
        }

        MlSignals ml = new MlSignals(cropSignal(farmId, crop), yieldSignal(farmId));

        if ("AVAILABLE".equals(ml.cropRecommendation().status())
                && Boolean.FALSE.equals(ml.cropRecommendation().matchesCurrentCrop())) {
            notes.add("The crop recommendation model suggests " + ml.cropRecommendation().recommendedCrop()
                    + " for these soil and weather values, not the current crop " + crop
                    + ". Actions are still evaluated for the crop in the field.");
        }
        notes.add("Risk relevance is matched by action category: irrigation/water to WATER,"
                + " fertilizer/nutrient/soil to SOIL, pest/disease to DISEASE.");

        long recommended = result.actions().stream()
                .filter(a -> ActionPrioritizer.RECOMMENDED.equals(a.decision())).count();

        String message = result.actions().isEmpty()
                ? "No agricultural actions could be evaluated for this farm."
                : recommended + " of " + result.actions().size()
                        + " evaluated action(s) are recommended.";

        return new DecisionResponse(
                true,
                message,
                farm.getId(),
                farm.getFarmName(),
                farmState.getCrop(),
                farmState.getGrowthStage(),
                LocalDateTime.now(),
                summarize(context),
                risk.overallStatus(),
                risk.risks(),
                ml,
                result.resources(),
                result.actions(),
                notes);
    }

    private ContextSummary summarize(ContextInterpretationResponse context) {
        int high = 0, medium = 0, low = 0, unknown = 0;
        List<ContextItem> attention = new ArrayList<>();
        List<ContextItem> items = context.context() == null ? List.of() : context.context();
        for (ContextItem item : items) {
            String s = item.severity() == null ? "NEEDS_DATA" : item.severity().toUpperCase(Locale.ROOT);
            switch (s) {
                case "HIGH" -> { high++; attention.add(item); }
                case "MEDIUM" -> { medium++; attention.add(item); }
                case "LOW" -> low++;
                case "INFO" -> { }
                default -> { unknown++; attention.add(item); }
            }
        }
        return new ContextSummary(high, medium, low, unknown, attention);
    }

    private CropSignal cropSignal(Long farmId, String currentCrop) {
        try {
            CropRecommendationResponse r = cropRecommendationService.recommendCrop(farmId);
            if (r == null || !r.isSuccess() || r.getRecommendedCrop() == null) {
                return new CropSignal("UNAVAILABLE",
                        r == null ? "No response from the crop recommendation service." : r.getMessage(),
                        null, null, null);
            }
            Double top = r.getTopRecommendations() == null || r.getTopRecommendations().isEmpty()
                    ? null
                    : r.getTopRecommendations().get(0).getProbability();
            Boolean matches = currentCrop == null ? null
                    : r.getRecommendedCrop().trim().equalsIgnoreCase(currentCrop);
            return new CropSignal("AVAILABLE", r.getMessage(), r.getRecommendedCrop(), top, matches);
        } catch (RuntimeException e) {
            return new CropSignal("UNAVAILABLE",
                    "Crop recommendation service unavailable: " + e.getMessage(), null, null, null);
        }
    }

    private YieldSignal yieldSignal(Long farmId) {
        try {
            YieldPredictionResponse r = yieldPredictionService.predictYield(farmId);
            if (r == null || !r.success() || r.predictedYieldKgPerHa() == null) {
                return new YieldSignal("UNAVAILABLE",
                        r == null ? "No response from the yield prediction service." : r.message(), null);
            }
            return new YieldSignal("AVAILABLE", r.message(), r.predictedYieldKgPerHa());
        } catch (ResponseStatusException e) {
            return new YieldSignal("UNAVAILABLE",
                    "Yield prediction unavailable: " + e.getReason(), null);
        } catch (RuntimeException e) {
            return new YieldSignal("UNAVAILABLE",
                    "Yield prediction unavailable: " + e.getMessage(), null);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
