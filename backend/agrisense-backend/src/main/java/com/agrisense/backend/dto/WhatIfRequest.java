package com.agrisense.backend.dto;

/*
 * Values the farmer changes in a copy of the current farm state.
 * A field left out (null) keeps its current value. The crop is not
 * editable here: the actions and the yield model depend on it, so a
 * crop change is a new farm state, not a scenario.
 */
public class WhatIfRequest {

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

    public String getGrowthStage() { return growthStage; }
    public void setGrowthStage(String growthStage) { this.growthStage = growthStage; }
    public Double getSoilMoisture() { return soilMoisture; }
    public void setSoilMoisture(Double soilMoisture) { this.soilMoisture = soilMoisture; }
    public Double getSoilPh() { return soilPh; }
    public void setSoilPh(Double soilPh) { this.soilPh = soilPh; }
    public Double getNitrogen() { return nitrogen; }
    public void setNitrogen(Double nitrogen) { this.nitrogen = nitrogen; }
    public Double getPhosphorus() { return phosphorus; }
    public void setPhosphorus(Double phosphorus) { this.phosphorus = phosphorus; }
    public Double getPotassium() { return potassium; }
    public void setPotassium(Double potassium) { this.potassium = potassium; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getHumidity() { return humidity; }
    public void setHumidity(Double humidity) { this.humidity = humidity; }
    public Double getRainfall() { return rainfall; }
    public void setRainfall(Double rainfall) { this.rainfall = rainfall; }
    public Double getWaterAvailable() { return waterAvailable; }
    public void setWaterAvailable(Double waterAvailable) { this.waterAvailable = waterAvailable; }
    public Integer getLabourAvailable() { return labourAvailable; }
    public void setLabourAvailable(Integer labourAvailable) { this.labourAvailable = labourAvailable; }
    public Double getBudgetAvailable() { return budgetAvailable; }
    public void setBudgetAvailable(Double budgetAvailable) { this.budgetAvailable = budgetAvailable; }
    public String getCropHealth() { return cropHealth; }
    public void setCropHealth(String cropHealth) { this.cropHealth = cropHealth; }
    public String getDiseaseStatus() { return diseaseStatus; }
    public void setDiseaseStatus(String diseaseStatus) { this.diseaseStatus = diseaseStatus; }
}
