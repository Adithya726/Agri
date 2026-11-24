package com.agrisense.backend.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/*
 * What the farmer reports about one item of an accepted plan:
 * done or skipped, on which date, with an optional note.
 * One record per plan item; reporting again updates it.
 */
@Entity
@Table(name = "action_executions")
public class ActionExecution {

    public static final String DONE = "DONE";
    public static final String SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_plan_item_id", nullable = false, unique = true)
    @JsonIgnore
    private ActionPlanItem actionPlanItem;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDate executedOn;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime recordedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ActionPlanItem getActionPlanItem() {
        return actionPlanItem;
    }

    public void setActionPlanItem(ActionPlanItem actionPlanItem) {
        this.actionPlanItem = actionPlanItem;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getExecutedOn() {
        return executedOn;
    }

    public void setExecutedOn(LocalDate executedOn) {
        this.executedOn = executedOn;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
