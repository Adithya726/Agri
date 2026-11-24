package com.agrisense.backend.dto;

import java.time.LocalDate;

public class ActionExecutionRequest {

    private String status;
    private LocalDate executedOn;
    private String note;

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
}
