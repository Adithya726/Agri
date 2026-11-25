
package com.agrisense.backend.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.agrisense.backend.dto.ContextInterpretationResponse;
import com.agrisense.backend.dto.ContextInterpretationResponse.ContextItem;
import com.agrisense.backend.dto.RiskAnalysisResponse;
import com.agrisense.backend.dto.RiskAnalysisResponse.RiskFinding;

@Service
public class RiskAnalysisServiceImpl implements RiskAnalysisService {

    private final ContextInterpretationService contextInterpretationService;

    public RiskAnalysisServiceImpl(
            ContextInterpretationService contextInterpretationService) {
        this.contextInterpretationService = contextInterpretationService;
    }

    @Override
    public RiskAnalysisResponse analyzeFarmRisks(Long farmId) {

        ContextInterpretationResponse contextResponse =
                contextInterpretationService.interpretFarmContext(farmId);
        return fromContext(farmId, contextResponse);
    }

    // Risk analysis of a farm state that is not saved (what-if analysis).
    @Override
    public RiskAnalysisResponse analyzeFarmRisks(
            com.agrisense.backend.model.Farm farm,
            com.agrisense.backend.model.FarmState farmState) {
        return fromContext(farm.getId(),
                contextInterpretationService.interpretFarmContext(farm, farmState));
    }

    private RiskAnalysisResponse fromContext(
            Long farmId, ContextInterpretationResponse contextResponse) {

        List<RiskFinding> risks = new ArrayList<>();

        if (contextResponse.context() == null
                || contextResponse.context().isEmpty()) {
            return new RiskAnalysisResponse(
                    true,
                    "No context findings are available for risk analysis.",
                    farmId,
                    contextResponse.crop(),
                    contextResponse.growthStage(),
                    "NEEDS_DATA",
                    risks
            );
        }

        boolean hasHigh = false;
        boolean hasMedium = false;
        boolean needsData = false;

        for (ContextItem item : contextResponse.context()) {

            String severity = normalize(item.severity());

            if ("NEEDS_DATA".equals(severity)) {
                needsData = true;
                risks.add(new RiskFinding(
                        item.category(),
                        item.parameter(),
                        item.value(),
                        "NEEDS_DATA",
                        item.interpretation(),
                        "INSUFFICIENT_DATA"
                ));
                continue;
            }

            if ("HIGH".equals(severity)) {
                hasHigh = true;
            } else if ("MEDIUM".equals(severity)) {
                hasMedium = true;
            } else if (!"LOW".equals(severity)
                    && !"INFO".equals(severity)) {
                needsData = true;
                risks.add(new RiskFinding(
                        item.category(),
                        item.parameter(),
                        item.value(),
                        "NEEDS_DATA",
                        item.interpretation(),
                        "UNRECOGNIZED_SEVERITY"
                ));
                continue;
            }

            // LOW and INFO findings are retained as evidence,
            // but are not counted as elevated risks.
            if ("HIGH".equals(severity) || "MEDIUM".equals(severity)) {
                risks.add(new RiskFinding(
                        item.category(),
                        item.parameter(),
                        item.value(),
                        severity,
                        item.interpretation(),
                        "IDENTIFIED"
                ));
            }
        }

        String overallStatus;

        if (hasHigh) {
            overallStatus = "HIGH";
        } else if (hasMedium) {
            overallStatus = "MEDIUM";
        } else if (needsData) {
            overallStatus = "NEEDS_DATA";
        } else {
            overallStatus = "LOW";
        }

        String message = switch (overallStatus) {
            case "HIGH" ->
                    "One or more context items have HIGH severity. Review the listed evidence.";
            case "MEDIUM" ->
                    "One or more context items have MEDIUM severity. Review the listed evidence.";
            case "NEEDS_DATA" ->
                    "Some context information is unavailable or insufficient.";
            default ->
                    "No elevated context severity was identified by the existing interpretation rules.";
        };

        return new RiskAnalysisResponse(
                contextResponse.success(),
                message,
                contextResponse.farmId(),
                contextResponse.crop(),
                contextResponse.growthStage(),
                overallStatus,
                risks
        );
    }

    private String normalize(String severity) {
        return severity == null
                ? "NEEDS_DATA"
                : severity.trim().toUpperCase(Locale.ROOT);
    }
}
