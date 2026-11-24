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

    // Added in Phase G; existing fields are unchanged.
    private String decision;
    private String evidenceStatus;
    private String sourceReference;

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

    public String getDecision() {
        return decision;
    }

    public String getEvidenceStatus() {
        return evidenceStatus;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    // Decision engine outcome and the action's evidence, for display.
    public ActionPlanItemResponse withEvidence(
            String decision,
            String evidenceStatus,
            String sourceReference) {
        this.decision = decision;
        this.evidenceStatus = evidenceStatus;
        this.sourceReference = sourceReference;
        return this;
    }
}
