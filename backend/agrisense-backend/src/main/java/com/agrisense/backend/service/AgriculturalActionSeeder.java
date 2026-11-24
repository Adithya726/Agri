package com.agrisense.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.model.AgriculturalAction;
import com.agrisense.backend.repository.AgriculturalActionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/*
 * Loads the sourced agricultural actions in data/angrau-actions.json
 * into the existing agricultural_actions table. Each action keeps the
 * ANGRAU publication it came from as its source reference. Water,
 * labour and budget stay null because the sources do not state them
 * per action, so validation reports them as unknown instead of using
 * made-up figures.
 *
 * Idempotent: an action already present (same name, crop and growth
 * stage) is left untouched, including any edits an admin made.
 * Disable with agrisense.seed.actions.enabled=false.
 */
@Component
public class AgriculturalActionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AgriculturalActionSeeder.class);

    static final String DATA_FILE = "data/angrau-actions.json";
    static final String EVIDENCE_STATUS = "REFERENCE_SUPPORTED";

    private final AgriculturalActionRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public AgriculturalActionSeeder(
            AgriculturalActionRepository repository,
            ObjectMapper objectMapper,
            @Value("${agrisense.seed.actions.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (!enabled) {
            return;
        }
        try (InputStream in = new ClassPathResource(DATA_FILE).getInputStream()) {
            int added = seed(in);
            log.info("Sourced agricultural actions: {} added from {}", added, DATA_FILE);
        }
    }

    // Returns the number of actions inserted.
    int seed(InputStream json) throws IOException {
        List<AgriculturalAction> toSave = new ArrayList<>();
        for (AgriculturalAction action : read(json)) {
            if (!repository.existsByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                    action.getActionName(), action.getApplicableCrop(), action.getApplicableGrowthStage())) {
                toSave.add(action);
            }
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
        return toSave.size();
    }

    List<AgriculturalAction> read(InputStream json) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode sources = root.path("sources");
        List<AgriculturalAction> result = new ArrayList<>();

        for (JsonNode node : root.path("actions")) {
            String sourceKey = text(node, "source");
            String reference = sources.path(sourceKey).asString(null);
            if (reference == null || reference.isBlank()) {
                throw new IllegalStateException(
                        "Action '" + text(node, "name") + "' has no source reference in " + DATA_FILE);
            }

            AgriculturalAction action = new AgriculturalAction();
            action.setActionName(text(node, "name"));
            action.setCategory(text(node, "category"));
            action.setDescription(text(node, "description"));
            action.setApplicableCrop(text(node, "crop"));
            action.setApplicableGrowthStage(text(node, "stage"));
            action.setEvidenceStatus(EVIDENCE_STATUS);
            action.setSourceReference(reference);
            action.setActive(Boolean.TRUE);
            result.add(action);
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
