package com.agrisense.backend.service;

import com.agrisense.backend.dto.ActionExecutionRequest;
import com.agrisense.backend.dto.MonitoringResponse;

public interface MonitoringService {

    MonitoringResponse getFarmMonitoring(Long farmId);

    MonitoringResponse recordExecution(Long itemId, ActionExecutionRequest request);

    MonitoringResponse clearExecution(Long itemId);
}
