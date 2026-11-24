package com.agrisense.backend.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;

/*
 * A timestamped copy of a farm's state, written each time the state
 * is saved. farm_states keeps only the current values; this keeps the
 * history that monitoring compares against.
 */
@Entity
@Table(name = "farm_state_readings",
        indexes = @Index(name = "idx_reading_farm_time", columnList = "farm_id, recorded_at"))
public class FarmStateReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    @JsonIgnore
    private Farm farm;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    private String crop;
    private String growthStage;
    private Double soilMoisture;
    private Double soilPh;
    private Double nitrogen;
    private Double phosphorus;
    private Double potassium;
    private Double temperature;
    private Double humidity;
    private Double rainfall;
    private Double waterAvailable;
    private Integer labourAvailable;
    private Double budgetAvailable;
    private String cropHealth;
    private String diseaseStatus;

    public static FarmStateReading of(FarmState state, LocalDateTime recordedAt) {
        FarmStateReading r = new FarmStateReading();
        r.setFarm(state.getFarm());
        r.setRecordedAt(recordedAt);
        r.setCrop(state.getCrop());
        r.setGrowthStage(state.getGrowthStage());
        r.setSoilMoisture(state.getSoilMoisture());
        r.setSoilPh(state.getSoilPh());
        r.setNitrogen(state.getNitrogen());
        r.setPhosphorus(state.getPhosphorus());
        r.setPotassium(state.getPotassium());
        r.setTemperature(state.getTemperature());
        r.setHumidity(state.getHumidity());
        r.setRainfall(state.getRainfall());
        r.setWaterAvailable(state.getWaterAvailable());
        r.setLabourAvailable(state.getLabourAvailable());
        r.setBudgetAvailable(state.getBudgetAvailable());
        r.setCropHealth(state.getCropHealth());
        r.setDiseaseStatus(state.getDiseaseStatus());
        return r;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Farm getFarm() {
        return farm;
    }

    public void setFarm(Farm farm) {
        this.farm = farm;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }

    public String getCrop() {
        return crop;
    }

    public void setCrop(String crop) {
        this.crop = crop;
    }

    public String getGrowthStage() {
        return growthStage;
    }

    public void setGrowthStage(String growthStage) {
        this.growthStage = growthStage;
    }

    public Double getSoilMoisture() {
        return soilMoisture;
    }

    public void setSoilMoisture(Double soilMoisture) {
        this.soilMoisture = soilMoisture;
    }

    public Double getSoilPh() {
        return soilPh;
    }

    public void setSoilPh(Double soilPh) {
        this.soilPh = soilPh;
    }

    public Double getNitrogen() {
        return nitrogen;
    }

    public void setNitrogen(Double nitrogen) {
        this.nitrogen = nitrogen;
    }

    public Double getPhosphorus() {
        return phosphorus;
    }

    public void setPhosphorus(Double phosphorus) {
        this.phosphorus = phosphorus;
    }

    public Double getPotassium() {
        return potassium;
    }

    public void setPotassium(Double potassium) {
        this.potassium = potassium;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getHumidity() {
        return humidity;
    }

    public void setHumidity(Double humidity) {
        this.humidity = humidity;
    }

    public Double getRainfall() {
        return rainfall;
    }

    public void setRainfall(Double rainfall) {
        this.rainfall = rainfall;
    }

    public Double getWaterAvailable() {
        return waterAvailable;
    }

    public void setWaterAvailable(Double waterAvailable) {
        this.waterAvailable = waterAvailable;
    }

    public Integer getLabourAvailable() {
        return labourAvailable;
    }

    public void setLabourAvailable(Integer labourAvailable) {
        this.labourAvailable = labourAvailable;
    }

    public Double getBudgetAvailable() {
        return budgetAvailable;
    }

    public void setBudgetAvailable(Double budgetAvailable) {
        this.budgetAvailable = budgetAvailable;
    }

    public String getCropHealth() {
        return cropHealth;
    }

    public void setCropHealth(String cropHealth) {
        this.cropHealth = cropHealth;
    }

    public String getDiseaseStatus() {
        return diseaseStatus;
    }

    public void setDiseaseStatus(String diseaseStatus) {
        this.diseaseStatus = diseaseStatus;
    }
}
