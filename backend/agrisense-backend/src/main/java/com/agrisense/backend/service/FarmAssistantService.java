package com.agrisense.backend.service;

import com.agrisense.backend.dto.AssistantResponse;

public interface FarmAssistantService {

    AssistantResponse ask(Long farmId, String question);
}
