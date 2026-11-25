package com.agrisense.backend.service;

import java.time.LocalDate;
import java.util.List;

import com.agrisense.backend.dto.HistoryResponse;

public interface HistoryService {

    HistoryResponse getFarmHistory(Long farmId, List<String> types, LocalDate from, LocalDate to);
}
