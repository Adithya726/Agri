package com.agrisense.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
 * labour and budget stay null on the row: they depend on the farm's
 * area, so RequirementCatalog works them out per farm from
 * data/action-requirements.json and data/input-prices.json.
 *
 * Idempotent: an action is matched by name, crop and growth stage.
 * A new one is inserted. An existing sourced row (evidence status
 * REFERENCE_SUPPORTED) gets its category, description and source
 * updated from the file, so corrections to the JSON (for example
 * removing banned chemicals) reach the database. Admin-added actions,
 * the active flag and the stored requirement figures are not touched.
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
            Result result = seedOrUpdate(in);
            log.info("Sourced agricultural actions from {}: {} added, {} updated",
                    DATA_FILE, result.added(), result.updated());
        }
    }

    record Result(int added, int updated) {}

    // Returns the number of actions inserted.
    int seed(InputStream json) throws IOException {
        return seedOrUpdate(json).added();
    }

    Result seedOrUpdate(InputStream json) throws IOException {
        List<AgriculturalAction> toSave = new ArrayList<>();
        int added = 0;
        int updated = 0;
        for (AgriculturalAction action : read(json)) {
            Optional<AgriculturalAction> existing = repository
                    .findFirstByActionNameIgnoreCaseAndApplicableCropIgnoreCaseAndApplicableGrowthStageIgnoreCase(
                            action.getActionName(), action.getApplicableCrop(), action.getApplicableGrowthStage());
            if (existing.isEmpty()) {
                toSave.add(action);
                added++;
            } else if (EVIDENCE_STATUS.equals(existing.get().getEvidenceStatus())
                    && differs(existing.get(), action)) {
                AgriculturalAction row = existing.get();
                row.setCategory(action.getCategory());
                row.setDescription(action.getDescription());
                row.setSourceReference(action.getSourceReference());
                toSave.add(row);
                updated++;
            }
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
        return new Result(added, updated);
    }

    private static boolean differs(AgriculturalAction row, AgriculturalAction file) {
        return !Objects.equals(row.getCategory(), file.getCategory())
                || !Objects.equals(row.getDescription(), file.getDescription())
                || !Objects.equals(row.getSourceReference(), file.getSourceReference());
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
