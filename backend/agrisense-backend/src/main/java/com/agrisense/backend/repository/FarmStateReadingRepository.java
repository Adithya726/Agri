package com.agrisense.backend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.agrisense.backend.model.FarmStateReading;

public interface FarmStateReadingRepository
        extends JpaRepository<FarmStateReading, Long> {

    List<FarmStateReading> findByFarmIdOrderByRecordedAtDesc(Long farmId);

    Optional<FarmStateReading> findFirstByFarmIdOrderByRecordedAtDesc(Long farmId);

    Optional<FarmStateReading> findFirstByFarmIdAndRecordedAtLessThanOrderByRecordedAtDesc(
            Long farmId, LocalDateTime before);

    boolean existsByFarmId(Long farmId);

    void deleteByFarmId(Long farmId);
}
