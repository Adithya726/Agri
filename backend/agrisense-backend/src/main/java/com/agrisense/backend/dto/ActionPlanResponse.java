package com.agrisense.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

public class ActionPlanResponse {

    private Long id;
    private Long farmId;
    private String farmName;
    private String crop;
    private String growthStage;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private List<ActionPlanItemResponse> items;

    public ActionPlanResponse() {
    }

    public ActionPlanResponse(
            Long id,
            Long farmId,
            String farmName,
            String crop,
            String growthStage,
            String status,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt,
            List<ActionPlanItemResponse> items) {

        this.id = id;
        this.farmId = farmId;
        this.farmName = farmName;
        this.crop = crop;
        this.growthStage = growthStage;
        this.status = status;
        this.createdAt = createdAt;
        this.reviewedAt = reviewedAt;
        this.items = items;
    }

    public Long getId() {
        return id;
    }

    public Long getFarmId() {
        return farmId;
    }

    public String getFarmName() {
        return farmName;
    }

    public String getCrop() {
        return crop;
    }

    public String getGrowthStage() {
        return growthStage;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public List<ActionPlanItemResponse> getItems() {
        return items;
    }
}