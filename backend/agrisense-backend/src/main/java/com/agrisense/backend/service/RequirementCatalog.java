package com.agrisense.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.agrisense.backend.dto.StepRequirements;
import com.agrisense.backend.dto.StepRequirements.Cost;
import com.agrisense.backend.dto.StepRequirements.Input;
import com.agrisense.backend.dto.StepRequirements.Labour;
import com.agrisense.backend.dto.StepRequirements.LabourPart;
import com.agrisense.backend.dto.StepRequirements.Timing;
import com.agrisense.backend.dto.StepRequirements.Water;
import com.agrisense.backend.model.AgriculturalAction;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/*
 * Works out what each ANGRAU step needs on a farm, scaled by the
 * farm's area in acres:
 *   products  = dose per acre x area, priced at the pack rate
 *   labour    = person-days per acre x area, priced at the AP daily wage
 *   water     = depth (mm) x 4.047 m3 per mm per acre x area, plus spray water
 *
 * Doses and timings come from data/action-requirements.json; prices,
 * wages, labour and water figures from data/input-prices.json. Every
 * figure there carries its source URL. A part with no source is
 * reported as not sourced and never estimated.
 */
@Component
public class RequirementCatalog {

    static final String REQUIREMENTS_FILE = "data/action-requirements.json";
    static final String PRICES_FILE = "data/input-prices.json";
    static final String PRICE_NOTE = "Prices vary by shop. Each price shows the date it was read.";

    private final JsonNode prices;
    private final Map<String, JsonNode> byAction = new HashMap<>();
    private final List<String> banned = new ArrayList<>();
    private final double acresPerHectare;
    private final double m3PerCmPerAcre;
    private final double sprayLitresPerAcre;

    @Autowired
    public RequirementCatalog(ObjectMapper objectMapper) {
        this(read(objectMapper, PRICES_FILE), read(objectMapper, REQUIREMENTS_FILE));
    }

    RequirementCatalog(JsonNode prices, JsonNode requirements) {
        this.prices = prices;
        this.acresPerHectare = prices.path("conversions").path("acresPerHectare").asDouble();
        this.m3PerCmPerAcre = prices.path("conversions").path("m3PerCmPerAcre").asDouble();
        this.sprayLitresPerAcre = prices.path("water").path("SPRAY_LITRES_PER_ACRE").path("value").asDouble();
        for (JsonNode b : prices.path("banned")) {
            banned.add(b.path("chemical").asString().toLowerCase(Locale.ROOT));
        }
        for (JsonNode a : requirements.path("actions")) {
            for (JsonNode in : a.path("inputs")) {
                JsonNode product = prices.path("products").path(in.path("product").asString());
                if (product.isMissingNode()) {
                    throw new IllegalStateException("No price for product " + in.path("product").asString());
                }
                if (isBanned(product.path("name").asString())) {
                    throw new IllegalStateException("Banned chemical in action requirements: "
                            + product.path("name").asString());
                }
            }
            byAction.put(key(a.path("crop").asString(), a.path("name").asString()), a);
        }
    }

    private static JsonNode read(ObjectMapper mapper, String file) {
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static String key(String crop, String name) {
        return (crop == null ? "" : crop.trim().toLowerCase(Locale.ROOT)) + "|"
                + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    public boolean isBanned(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        return banned.stream().anyMatch(t::contains);
    }

    public boolean hasRequirements(AgriculturalAction action) {
        return action != null && byAction.containsKey(key(action.getApplicableCrop(), action.getActionName()));
    }

    /*
     * Requirements for one step. areaAcres null gives the figures for
     * one acre (areaAcres stays null so the caller can say "per acre").
     */
    public Optional<StepRequirements> requirementsFor(AgriculturalAction action, Double areaAcres, String season) {
        if (action == null) {
            return Optional.empty();
        }
        JsonNode a = byAction.get(key(action.getApplicableCrop(), action.getActionName()));
        if (a == null) {
            return Optional.empty();
        }
        double area = areaAcres != null && areaAcres > 0 ? areaAcres : 1.0;
        List<String> notSourced = new ArrayList<>();

        Timing timing = timing(a.path("timing"));
        if (timing.fromDay() == null) {
            notSourced.add("Exact day count for this step");
        }

        List<Input> inputs = new ArrayList<>();
        for (JsonNode in : a.path("inputs")) {
            inputs.add(input(in, area, season));
        }

        Labour labour = labour(a, area);
        if (!labour.sourced()) {
            notSourced.add("Workers for " + labour.notSourced());
        }

        Water water = water(a, area);
        if (!water.sourced()) {
            notSourced.add("Water for this step");
        }

        Cost cost = cost(inputs, labour);

        List<String> safety = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (JsonNode n : a.path("notes")) {
            String text = n.asString();
            if (isBanned(text)) {
                safety.add(text);
            } else {
                notes.add(text);
            }
        }

        return Optional.of(new StepRequirements(
                areaAcres != null && areaAcres > 0 ? areaAcres : null,
                season, timing, inputs, labour, water, cost, notSourced, safety, notes, PRICE_NOTE));
    }

    private static Timing timing(JsonNode t) {
        return new Timing(
                t.path("text").asString(null),
                intOrNull(t.path("fromDay")),
                intOrNull(t.path("toDay")),
                t.path("countFrom").asString(null),
                t.path("source").asString(null));
    }

    private Input input(JsonNode in, double area, String season) {
        String key = in.path("product").asString();
        JsonNode p = prices.path("products").path(key);
        String unit = in.path("unit").asString();

        double[] perAcre;
        if (in.has("nutrient")) {
            JsonNode n = in.path("nutrient");
            double content = p.path("nutrients").path(n.path("name").asString()).asDouble();
            boolean perHa = n.has("perHa");
            double[] nutrient = seasonRange(perHa ? n.path("perHa") : n.path("perAcre"), season);
            double divisor = content * (perHa ? acresPerHectare : 1.0);
            perAcre = new double[] { nutrient[0] / divisor, nutrient[1] / divisor };
        } else if (in.has("perHa")) {
            double[] ha = range(in.path("perHa"));
            perAcre = new double[] { ha[0] / acresPerHectare, ha[1] / acresPerHectare };
        } else {
            perAcre = range(in.path("perAcre"));
        }

        double qMin = round2(perAcre[0] * area);
        double qMax = round2(perAcre[1] * area);
        double packSize = p.path("packSize").asDouble();
        String packUnit = p.path("packUnit").asString();
        double pricePerUnit = p.path("price").asDouble() / (toBase(packSize, packUnit) / toBase(1, unit));

        return new Input(
                key,
                p.path("name").asString(),
                qMin,
                qMax,
                unit,
                round0(qMin * pricePerUnit),
                round0(qMax * pricePerUnit),
                in.path("choice").asString(null),
                in.path("optional").asBoolean(false),
                in.path("purpose").asString(null),
                p.path("price").asDouble(),
                packSize,
                packUnit,
                p.path("priceDate").asString(null),
                p.path("shop").asString(null),
                p.path("estimate").asBoolean(false),
                in.path("source").asString(null),
                p.path("source").asString(null));
    }

    private Labour labour(JsonNode a, double area) {
        String notSourced = a.path("labourNotSourced").asString(null);
        List<LabourPart> parts = new ArrayList<>();
        for (JsonNode l : a.path("labour")) {
            JsonNode ref = prices.path("labour").path(l.path("ref").asString());
            JsonNode wage = prices.path("wages").path(ref.path("wage").asString());
            double[] times = range(l.path("times"));
            double perAcre = ref.path("personDays").asDouble()
                    / ("ha".equals(ref.path("per").asString()) ? acresPerHectare : 1.0);
            double men = wage.path("men").asDouble();
            double women = wage.has("women") ? wage.path("women").asDouble() : men;
            double pdMin = round2(perAcre * area * times[0]);
            double pdMax = round2(perAcre * area * times[1]);
            parts.add(new LabourPart(
                    operationLabel(l.path("ref").asString()),
                    pdMin, pdMax, women, men,
                    round0(pdMin * women), round0(pdMax * men),
                    wage.has("women") ? "Women's to men's daily wage" : "Men's daily wage",
                    ref.path("note").asString(null),
                    ref.path("source").asString(null),
                    wage.path("source").asString(null)));
        }
        if (parts.isEmpty()) {
            return new Labour(false, null, null, null, null, parts,
                    notSourced == null ? "this step" : notSourced);
        }
        double pdMin = 0, pdMax = 0, cMin = 0, cMax = 0;
        for (LabourPart p : parts) {
            pdMin += p.personDaysMin();
            pdMax += p.personDaysMax();
            cMin += p.costMin();
            cMax += p.costMax();
        }
        return new Labour(true, round2(pdMin), round2(pdMax), round0(cMin), round0(cMax), parts, null);
    }

    private Water water(JsonNode a, double area) {
        JsonNode w = a.path("water");
        double[] sprays = a.has("sprays") && !a.path("sprays").isNull() ? range(a.path("sprays")) : null;
        Double sprayLitres = sprays == null ? null : round0(sprays[1] * sprayLitresPerAcre * area);
        double sprayM3Min = sprays == null ? 0 : sprays[0] * sprayLitresPerAcre * area / 1000.0;
        double sprayM3Max = sprays == null ? 0 : sprays[1] * sprayLitresPerAcre * area / 1000.0;
        String spraySource = prices.path("water").path("SPRAY_LITRES_PER_ACRE").path("source").asString(null);

        if (w.has("includedIn")) {
            return new Water(true, 0.0, 0.0, null, null, sprayLitres,
                    "Counted in another step", w.path("includedIn").asString(), null, null);
        }
        if (w.has("mm")) {
            double[] mm = range(w.path("mm"));
            double m3Min = mm[0] / 10.0 * m3PerCmPerAcre * area + sprayM3Min;
            double m3Max = mm[1] / 10.0 * m3PerCmPerAcre * area + sprayM3Max;
            return new Water(true, round1(m3Min), round1(m3Max), mm[0], mm[1], sprayLitres,
                    w.path("label").asString(null), null, null, w.path("source").asString(null));
        }
        if (sprays != null) {
            return new Water(true, round1(sprayM3Min), round1(sprayM3Max), null, null, sprayLitres,
                    "Spray water, 200 litres per acre per spray", null, null, spraySource);
        }
        return new Water(false, null, null, null, null, null, null, null,
                w.path("notSourced").asString("No water amount is given for this step"), null);
    }

    private static Cost cost(List<Input> inputs, Labour labour) {
        double pMin = 0, pMax = 0;
        Map<String, double[]> choices = new LinkedHashMap<>();
        for (Input in : inputs) {
            if (in.optional()) {
                continue;
            }
            if (in.choice() == null) {
                pMin += in.costMin();
                pMax += in.costMax();
            } else {
                double[] c = choices.computeIfAbsent(in.choice(),
                        k -> new double[] { Double.MAX_VALUE, 0 });
                c[0] = Math.min(c[0], in.costMin());
                c[1] = Math.max(c[1], in.costMax());
            }
        }
        for (double[] c : choices.values()) {
            pMin += c[0];
            pMax += c[1];
        }
        boolean withLabour = labour.sourced();
        double lMin = withLabour ? labour.costMin() : 0;
        double lMax = withLabour ? labour.costMax() : 0;
        return new Cost(round0(pMin + lMin), round0(pMax + lMax), round0(pMin), round0(pMax),
                withLabour ? round0(lMin) : null, withLabour ? round0(lMax) : null, withLabour);
    }

    /*
     * Straight fertilizer for an N:P2O5:K2O dose in kg/ha, per acre:
     * DAP for all the P2O5, urea for the N that DAP does not cover,
     * MOP for the K2O. Returns product -> {kg per acre, rupees}.
     */
    public Map<String, double[]> fertilizerPerAcre(double nPerHa, double pPerHa, double kPerHa) {
        JsonNode products = prices.path("products");
        double dapP = products.path("DAP").path("nutrients").path("P2O5").asDouble();
        double dapN = products.path("DAP").path("nutrients").path("N").asDouble();
        double ureaN = products.path("UREA").path("nutrients").path("N").asDouble();
        double mopK = products.path("MOP").path("nutrients").path("K2O").asDouble();

        double dap = pPerHa / dapP;
        double urea = Math.max(0, nPerHa - dap * dapN) / ureaN;
        double mop = kPerHa / mopK;

        Map<String, double[]> out = new LinkedHashMap<>();
        put(out, "DAP", dap / acresPerHectare, products.path("DAP"));
        put(out, "UREA", urea / acresPerHectare, products.path("UREA"));
        put(out, "MOP", mop / acresPerHectare, products.path("MOP"));
        return out;
    }

    private static void put(Map<String, double[]> out, String key, double kg, JsonNode product) {
        double perKg = product.path("price").asDouble() / product.path("packSize").asDouble();
        out.put(key, new double[] { round1(kg), round0(kg * perKg) });
    }

    private static String operationLabel(String ref) {
        return switch (ref) {
            case "RICE_TRANSPLANTING" -> "Transplanting";
            case "RICE_HAND_WEEDING" -> "Hand weeding, two rounds";
            case "RICE_SPRAY", "MAIZE_SPRAY" -> "Spraying";
            case "RICE_HARVEST" -> "Harvest and threshing";
            case "MAIZE_THINNING" -> "Thinning";
            case "MAIZE_WEEDING_EARTHING" -> "Weeding and earthing up";
            case "MAIZE_HARVEST" -> "Harvest and shelling";
            default -> ref;
        };
    }

    private static double[] seasonRange(JsonNode node, String season) {
        String s = season == null ? "" : season.trim().toLowerCase(Locale.ROOT);
        if (node.has(s)) {
            return range(node.path(s));
        }
        double min = Double.MAX_VALUE, max = 0;
        for (JsonNode r : node) {
            double[] v = range(r);
            min = Math.min(min, v[0]);
            max = Math.max(max, v[1]);
        }
        return new double[] { min, max };
    }

    private static double[] range(JsonNode node) {
        if (node.isNumber()) {
            return new double[] { node.asDouble(), node.asDouble() };
        }
        return new double[] { node.path("min").asDouble(), node.path("max").asDouble() };
    }

    private static double toBase(double amount, String unit) {
        return switch (unit) {
            case "kg", "l" -> amount * 1000;
            default -> amount;
        };
    }

    private static Integer intOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asInt();
    }

    static double round0(double v) {
        return Math.round(v);
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
