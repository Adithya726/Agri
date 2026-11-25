package com.agrisense.backend.dto;

import java.util.List;

/*
 * What one ANGRAU step needs on one farm: when to do it, the products
 * and amounts for the farm's area, the cost in rupees, the workers
 * (person-days) and the water (m3). Every figure carries its source.
 *
 * Amounts are ranges (min to max) because the source gives a range,
 * a choice of products, or men's and women's wages. A part with no
 * source has sourced = false and is listed in notSourced; it is never
 * estimated.
 */
public record StepRequirements(
        Double areaAcres,
        String season,
        Timing timing,
        List<Input> inputs,
        Labour labour,
        Water water,
        Cost cost,
        List<String> notSourced,
        List<String> safetyNotes,
        List<String> notes,
        String priceNote
) {

    // fromDay/toDay are days after countFrom (SOWING, TRANSPLANTING or
    // EMERGENCE); null when the source gives a stage but no day count.
    public record Timing(
            String text,
            Integer fromDay,
            Integer toDay,
            String countFrom,
            String source
    ) {}

    // choice groups products the step lists as "one of"; optional
    // inputs are only needed in a situation the step names.
    public record Input(
            String product,
            String name,
            Double quantityMin,
            Double quantityMax,
            String unit,
            Double costMin,
            Double costMax,
            String choice,
            boolean optional,
            String purpose,
            Double packPrice,
            Double packSize,
            String packUnit,
            String priceDate,
            String shop,
            boolean priceEstimate,
            String doseSource,
            String priceSource
    ) {}

    public record LabourPart(
            String operation,
            Double personDaysMin,
            Double personDaysMax,
            Double wageMin,
            Double wageMax,
            Double costMin,
            Double costMax,
            String wageType,
            String note,
            String source,
            String wageSource
    ) {}

    public record Labour(
            boolean sourced,
            Double personDaysMin,
            Double personDaysMax,
            Double costMin,
            Double costMax,
            List<LabourPart> parts,
            String notSourced
    ) {}

    // m3 for the farm. mm is the depth the source gives; sprayLitres the
    // spray water. includedIn names the step that already counts it.
    public record Water(
            boolean sourced,
            Double m3Min,
            Double m3Max,
            Double mmMin,
            Double mmMax,
            Double sprayLitres,
            String label,
            String includedIn,
            String notSourced,
            String source
    ) {}

    // Products (cheapest to dearest choice) plus labour (women's to
    // men's wage). includesLabour is false when labour is not sourced.
    public record Cost(
            Double min,
            Double max,
            Double productsMin,
            Double productsMax,
            Double labourMin,
            Double labourMax,
            boolean includesLabour
    ) {}
}
