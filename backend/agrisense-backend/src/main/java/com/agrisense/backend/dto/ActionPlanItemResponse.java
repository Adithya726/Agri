package com.agrisense.backend.dto;

public class ActionPlanItemResponse {

    private Long id;
    private Long agriculturalActionId;
    private String actionName;
    private String category;
    private String description;
    private Integer sequenceOrder;
    private String validationStatus;
    private String validationMessage;

    public ActionPlanItemResponse() {
    }

    public ActionPlanItemResponse(
            Long id,
            Long agriculturalActionId,
            String actionName,
            String category,
            String description,
            Integer sequenceOrder,
            String validationStatus,
            String validationMessage) {

        this.id = id;
        this.agriculturalActionId = agriculturalActionId;
        this.actionName = actionName;
        this.category = category;
        this.description = description;
        this.sequenceOrder = sequenceOrder;
        this.validationStatus = validationStatus;
        this.validationMessage = validationMessage;
    }

    public Long getId() {
        return id;
    }

    public Long getAgriculturalActionId() {
        return agriculturalActionId;
    }

    public String getActionName() {
        return actionName;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public Integer getSequenceOrder() {
        return sequenceOrder;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public String getValidationMessage() {
        return validationMessage;
    }
}