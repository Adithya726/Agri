package com.agrisense.backend.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.agrisense.backend.model.FarmState;
import com.agrisense.backend.model.FarmStateReading;
import com.agrisense.backend.repository.FarmStateReadingRepository;
import com.agrisense.backend.repository.FarmStateRepository;

/*
 * Farms saved before readings existed have a current state but no
 * history. Give each of them one starting reading (the state as it is
 * at startup) so monitoring has a baseline. Farms that already have
 * readings are left alone.
 */
@Component
public class FarmStateReadingBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FarmStateReadingBackfill.class);

    private final FarmStateRepository farmStateRepository;
    private final FarmStateReadingRepository readingRepository;

    public FarmStateReadingBackfill(FarmStateRepository farmStateRepository,
                                    FarmStateReadingRepository readingRepository) {
        this.farmStateRepository = farmStateRepository;
        this.readingRepository = readingRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int added = backfill(LocalDateTime.now());
        if (added > 0) {
            log.info("Farm state readings: {} starting reading(s) added", added);
        }
    }

    int backfill(LocalDateTime now) {
        int added = 0;
        for (FarmState state : farmStateRepository.findAll()) {
            if (state.getFarm() != null
                    && !readingRepository.existsByFarmId(state.getFarm().getId())) {
                readingRepository.save(FarmStateReading.of(state, now));
                added++;
            }
        }
        return added;
    }
}
