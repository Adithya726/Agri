package com.agrisense.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.agrisense.backend.dto.ConstraintValidationResponse;
import com.agrisense.backend.dto.CropRecommendationResponse;
import com.agrisense.backend.dto.DecisionResponse.CropSignal;
import com.agrisense.backend.dto.DecisionResponse.RankedAction;
import com.agrisense.backend.dto.DecisionResponse.YieldSignal;
import com.agrisense.backend.dto.MonitoringResponse.FieldChange;
import com.agrisense.backend.dto.MonitoringResponse.Reading;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.WhatIfRequest;
import com.agrisense.backend.dto.WhatIfResponse;
import com.agrisense.backend.dto.WhatIfResponse.ActionDifference;
import com.agrisense.backend.dto.WhatIfResponse.Scenario;
import com.agrisense.backend.dto.YieldPredictionResponse;
import com.agrisense.backend.exception.InvalidRequestException;
import com.agrisense.backend.exception.ResourceNotFoundException;
import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.model.Farm;
import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import com.agrisense.backend.repository.FarmRepository;
import com.agrisense.backend.repository.FarmStateRepository;
import com.agrisense.backend.validation.FarmStateValidator;

/*
 * What-if analysis. The farmer's edits are applied to a copy of the
 * saved farm state; the copy is validated with FarmStateValidator and
 * then put through the same risk analysis, constraint validation and
 * ActionPrioritizer as a real plan, over the same actions a new plan
 * would use (active, reference-supported, matching crop and stage).
 * Nothing is saved.
 */
@Service
public class WhatIfServiceImpl implements WhatIfService {

    static final String YIELD_NOTE = "The yield model uses year, state, district, crop and farm area."
            + " None of the what-if fields change those inputs, so the prediction is the same for both.";

    private final FarmRepository farmRepository;
    private final FarmStateRepository farmStateRepository;
    private final AgriculturalActionRepository actionRepository;
    private final RiskAnalysisService riskAnalysisService;
    private final ConstraintValidationService constraintValidationService;
    private final ActionPrioritizer actionPrioritizer;
    private final CropRecommendationService cropRecommendationService;
    private final YieldPredictionService yieldPredictionService;

    public WhatIfServiceImpl(
            FarmRepository farmRepository,
            FarmStateRepository farmStateRepository,
            AgriculturalActionRepository actionRepository,
            RiskAnalysisService riskAnalysisService,
            ConstraintValidationService constraintValidationService,
            ActionPrioritizer actionPrioritizer,
            CropRecommendationService cropRecommendationService,
            YieldPredictionService yieldPredictionService) {
        this.farmRepository = farmRepository;
        this.farmStateRepository = farmStateRepository;
        this.actionRepository = actionRepository;
        this.riskAnalysisService = riskAnalysisService;
        this.constraintValidationService = constraintValidationService;
        this.actionPrioritizer = actionPrioritizer;
        this.cropRecommendationService = cropRecommendationService;
        this.yieldPredictionService = yieldPredictionService;
    }

    @Override
    @Transactional(readOnly = true)
    public WhatIfResponse analyze(Long farmId, WhatIfRequest request) {

        if (request == null) {
            throw new InvalidRequestException("What-if values are required");
        }

        Farm farm = farmRepository.findById(farmId)
                .orElseThrow(() -> new ResourceNotFoundException("Farm not found"));

        FarmState saved = farmStateRepository.findByFarm(farm)
                .orElseThrow(() -> new InvalidRequestException(
                        "Farm state not found. Update the farm state first."));

        FarmState current = copy(saved);
        FarmState edited = apply(copy(saved), request);
        FarmStateValidator.validate(edited);

        List<AgriculturalAction> active = actionRepository.findByActiveTrue();
        List<String> notes = new ArrayList<>();

        Scenario now = scenario(farm, current, active);
        Scenario whatIf = scenario(farm, edited, active);

        LocalDateTime t = LocalDateTime.now();
        List<FieldChange> changes = MonitoringServiceImpl.compare(
                FarmStateReading.of(current, t), FarmStateReading.of(edited, t));

        if (changes.isEmpty()) {
            notes.add("No value was changed, so both columns show the saved farm state.");
        }
        if (now.evaluatedActions() == 0) {
            notes.add("No reference-supported action matches " + current.getCrop() + " at "
                    + current.getGrowthStage() + " in the saved state.");
        }
        if (whatIf.evaluatedActions() == 0) {
            notes.add("No reference-supported action matches " + edited.getCrop() + " at "
                    + edited.getGrowthStage() + " in the what-if state.");
        }
        notes.add("Actions are the ones a new plan would use: active, reference-supported, and for"
                + " the crop and growth stage of each state. Actions without water, labour or budget"
                + " figures in their source stay NEEDS_DATA in both columns.");
        notes.add(YIELD_NOTE);
        notes.add("Nothing here is saved. Update the farm state to make a change real.");

        return new WhatIfResponse(
                farm.getId(),
                farm.getFarmName(),
                current.getCrop(),
                changes,
                now,
                whatIf,
                differences(now.actions(), whatIf.actions()),
                yieldSignal(farmId),
                notes);
    }

    Scenario scenario(Farm farm, FarmState state, List<AgriculturalAction> active) {

        RiskAnalysisResponse risk = riskAnalysisService.analyzeFarmRisks(farm, state);

        List<ActionPrioritizer.Candidate> candidates = new ArrayList<>();
        for (AgriculturalAction action : applicable(active, state)) {
            ConstraintValidationResponse validation =
                    constraintValidationService.validateAction(farm.getId(), state, action);
            candidates.add(new ActionPrioritizer.Candidate(action, validation));
        }

        ActionPrioritizer.Result result = actionPrioritizer.prioritize(candidates, risk, state);
        List<RankedAction> ranked = result.actions();

        return new Scenario(
                toReading(state),
                risk.overallStatus(),
                risk.risks(),
                ranked.size(),
                count(ranked, ActionPrioritizer.RECOMMENDED),
                count(ranked, ActionPrioritizer.DEFERRED),
                count(ranked, ActionPrioritizer.NEEDS_DATA),
                count(ranked, ActionPrioritizer.REJECTED),
                ranked,
                result.resources(),
                cropSignal(farm.getId(), state));
    }

    // Same selection ActionPlanServiceImpl.generateDraft makes.
    static List<AgriculturalAction> applicable(List<AgriculturalAction> active, FarmState state) {
        String crop = state.getCrop() == null ? "" : state.getCrop().trim();
        String stage = state.getGrowthStage() == null ? "" : state.getGrowthStage().trim();
        return active.stream()
                .filter(a -> a.getApplicableCrop() != null && a.getApplicableGrowthStage() != null)
                .filter(a -> a.getApplicableCrop().trim().equalsIgnoreCase(crop))
                .filter(a -> a.getApplicableGrowthStage().trim().equalsIgnoreCase(stage))
                .filter(ActionPrioritizer::isReferenceSupported)
                .toList();
    }

    static List<ActionDifference> differences(List<RankedAction> before, List<RankedAction> after) {
        Map<Long, RankedAction> a = new LinkedHashMap<>();
        before.forEach(r -> a.put(r.actionId(), r));
        Map<Long, RankedAction> b = new LinkedHashMap<>();
        after.forEach(r -> b.put(r.actionId(), r));
        List<ActionDifference> out = new ArrayList<>();
        for (RankedAction r : before) {
            RankedAction other = b.get(r.actionId());
            String d = other == null ? null : other.decision();
            if (!Objects.equals(r.decision(), d)) {
                out.add(new ActionDifference(r.actionId(), r.actionName(), r.decision(), d));
            }
        }
        for (RankedAction r : after) {
            if (!a.containsKey(r.actionId())) {
                out.add(new ActionDifference(r.actionId(), r.actionName(), null, r.decision()));
            }
        }
        return out;
    }

    static FarmState copy(FarmState s) {
        FarmState c = new FarmState(s.getFarm(), s.getCrop(), s.getGrowthStage(),
                s.getSoilMoisture(), s.getSoilPh(), s.getNitrogen(), s.getPhosphorus(),
                s.getPotassium(), s.getTemperature(), s.getHumidity(), s.getRainfall(),
                s.getWaterAvailable(), s.getLabourAvailable(), s.getBudgetAvailable(),
                s.getCropHealth(), s.getDiseaseStatus());
        return c;
    }

    static FarmState apply(FarmState s, WhatIfRequest r) {
        if (r.getGrowthStage() != null && !r.getGrowthStage().trim().isEmpty()) {
            s.setGrowthStage(r.getGrowthStage().trim());
        }
        if (r.getSoilMoisture() != null) s.setSoilMoisture(r.getSoilMoisture());
        if (r.getSoilPh() != null) s.setSoilPh(r.getSoilPh());
        if (r.getNitrogen() != null) s.setNitrogen(r.getNitrogen());
        if (r.getPhosphorus() != null) s.setPhosphorus(r.getPhosphorus());
        if (r.getPotassium() != null) s.setPotassium(r.getPotassium());
        if (r.getTemperature() != null) s.setTemperature(r.getTemperature());
        if (r.getHumidity() != null) s.setHumidity(r.getHumidity());
        if (r.getRainfall() != null) s.setRainfall(r.getRainfall());
        if (r.getWaterAvailable() != null) s.setWaterAvailable(r.getWaterAvailable());
        if (r.getLabourAvailable() != null) s.setLabourAvailable(r.getLabourAvailable());
        if (r.getBudgetAvailable() != null) s.setBudgetAvailable(r.getBudgetAvailable());
        if (r.getCropHealth() != null && !r.getCropHealth().trim().isEmpty()) {
            s.setCropHealth(r.getCropHealth().trim());
        }
        if (r.getDiseaseStatus() != null && !r.getDiseaseStatus().trim().isEmpty()) {
            s.setDiseaseStatus(r.getDiseaseStatus().trim());
        }
        return s;
    }

    private CropSignal cropSignal(Long farmId, FarmState state) {
        try {
            CropRecommendationResponse r = cropRecommendationService.recommendCrop(farmId, state);
            if (r == null || !r.isSuccess() || r.getRecommendedCrop() == null) {
                return new CropSignal("UNAVAILABLE",
                        r == null ? "No response from the crop recommendation service." : r.getMessage(),
                        null, null, null);
            }
            Double top = r.getTopRecommendations() == null || r.getTopRecommendations().isEmpty()
                    ? null
                    : r.getTopRecommendations().get(0).getProbability();
            Boolean matches = state.getCrop() == null ? null
                    : r.getRecommendedCrop().trim().equalsIgnoreCase(state.getCrop().trim());
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
            return new YieldSignal("UNAVAILABLE", "Yield prediction unavailable: " + e.getReason(), null);
        } catch (RuntimeException e) {
            return new YieldSignal("UNAVAILABLE", "Yield prediction unavailable: " + e.getMessage(), null);
        }
    }

    private static int count(List<RankedAction> ranked, String decision) {
        return (int) ranked.stream().filter(r -> decision.equals(r.decision())).count();
    }

    private static Reading toReading(FarmState s) {
        return new Reading(null, null, s.getCrop(), s.getGrowthStage(),
                s.getSoilMoisture(), s.getSoilPh(), s.getNitrogen(), s.getPhosphorus(),
                s.getPotassium(), s.getTemperature(), s.getHumidity(), s.getRainfall(),
                s.getWaterAvailable(), s.getLabourAvailable(), s.getBudgetAvailable(),
                s.getCropHealth(), s.getDiseaseStatus());
    }
}
