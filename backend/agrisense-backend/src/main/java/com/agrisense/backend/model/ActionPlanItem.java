
package com.agrisense.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "action_plan_items")
public class ActionPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_plan_id", nullable = false)
    @JsonIgnore
    private ActionPlan actionPlan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agricultural_action_id", nullable = false)
    private AgriculturalAction agriculturalAction;

    @Column(nullable = false)
    private Integer sequenceOrder;

    @Column(nullable = false)
    private String validationStatus;

    @Column(length = 1000)
    private String validationMessage;

    public ActionPlanItem() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ActionPlan getActionPlan() {
        return actionPlan;
    }

    public void setActionPlan(ActionPlan actionPlan) {
        this.actionPlan = actionPlan;
    }

    public AgriculturalAction getAgriculturalAction() {
        return agriculturalAction;
    }

    public void setAgriculturalAction(
            AgriculturalAction agriculturalAction) {
        this.agriculturalAction = agriculturalAction;
    }

    public Integer getSequenceOrder() {
        return sequenceOrder;
    }

    public void setSequenceOrder(Integer sequenceOrder) {
        this.sequenceOrder = sequenceOrder;
    }

    public String getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(String validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }
}
