package com.hackathon.glasseshello;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A medicine, as the laptop's stream viewer sends it after reading the barcode on a package
 * and looking it up in the FDA's drug directory (openFDA). Nothing here is fixed to one
 * medicine; every field is read from what arrives, and a field left out comes through empty.
 *
 * <pre>
 * {
 *   "medicine": {
 *     "barcode": "0305730150408",
 *     "package_ndc": "0573-0150-40",     (empty when the barcode matched but no one package did)
 *     "fda_updated": "2026-11-20",
 *     "record": {                        (the directory's record for the product, as the FDA gives it)
 *       "product_ndc": "0573-0150", "brand_name": "ADVIL", "generic_name": "ibuprofen",
 *       "labeler_name": "...", "dosage_form": "TABLET, COATED", "route": ["ORAL"],
 *       "product_type": "HUMAN OTC DRUG", "marketing_category": "NDA", "dea_schedule": "CII",
 *       "active_ingredients": [{"name": "IBUPROFEN", "strength": "200 mg/1"}],
 *       "packaging": [{"package_ndc": "0573-0150-40", "description": "1 BOTTLE in 1 CARTON ... / 100 TABLET ..."}],
 *       "openfda": {"pharm_class_epc": ["Nonsteroidal Anti-inflammatory Drug [EPC]"], ...}
 *     },
 *     "label": {                         (a few lines of the drug's label; left out if it couldn't be read)
 *       "purpose": "Purpose Pain reliever/Fever reducer",
 *       "boxed_warning": "WARNING: BLEEDING RISK Warfarin sodium can cause ..."
 *     }
 *   }
 * }
 * </pre>
 */
final class Medicine {

    static final class Ingredient {
        final String name;
        /** Such as "200 MG" or "10 MG/5ML". */
        final String strength;

        Ingredient(String name, String strength) {
            this.name = name;
            this.strength = strength;
        }
    }

    static final class DrugClass {
        /** Such as "Nonsteroidal Anti-inflammatory Drug". */
        final String name;
        /** What kind of class it is: "CLASS", "ACTION", "EFFECT" or "CHEMISTRY". */
        final String kind;

        DrugClass(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    /** The message exactly as it arrived, to tell a new medicine from the one already showing. */
    final String source;
    final String barcode;
    /** The package that was scanned, such as "0573-0150-40", or empty. */
    final String packageNdc;
    /** The product, whatever its package size, such as "0573-0150". */
    final String productNdc;
    final String brandName;
    final String genericName;
    /** The company whose name is on the label. */
    final String labeler;
    /** Such as "TABLET, COATED". */
    final String form;
    /** Such as "ORAL". */
    final String route;
    /** "OTC", "RX", or the directory's own words for another kind of product. */
    final String type;
    /** How it came to market, such as "NDA" or "OTC MONOGRAPH DRUG". */
    final String marketing;
    /** The DEA schedule of a controlled substance, such as "CII", or empty. */
    final String schedule;
    /** What an over-the-counter label says it is for, such as "Pain reliever/Fever reducer", or empty. */
    final String purpose;
    /** What a prescription label says it is indicated for, cut to its first clause, or empty. */
    final String indication;
    /** Whether a label was read at all: without one, nothing is known about a boxed warning. */
    final boolean labelRead;
    final boolean boxedWarning;
    /** The boxed warning's headline, such as "BLEEDING RISK", or empty. */
    final String boxedWarningTitle;
    final List<Ingredient> ingredients;
    final List<DrugClass> classes;
    /** The scanned package from the outside in: "1 BOTTLE IN 1 CARTON", "100 TABLET, COATED IN 1 BOTTLE". */
    final List<String> packageLines;
    /** How many package sizes the product is listed in. */
    final int packageSizes;
    /** The day the directory was last updated, as "11/20/2026", or empty. */
    final String fdaDate;

    /** Whether the text is a medicine rather than a patient report. Throws if it is not a JSON object. */
    static boolean isMedicine(String json) throws JSONException {
        return new JSONObject(json).optJSONObject("medicine") != null;
    }

    /** Throws if the text is not a JSON object, or has no medicine with a name or code to show. */
    static Medicine parse(String json) throws JSONException {
        return new Medicine(json);
    }

    private Medicine(String json) throws JSONException {
        source = json.trim();
        JSONObject medicine = object(new JSONObject(source), "medicine");
        JSONObject record = object(medicine, "record");
        JSONObject label = medicine.optJSONObject("label");

        barcode = string(medicine, "barcode");
        packageNdc = string(medicine, "package_ndc");
        productNdc = string(record, "product_ndc");
        brandName = string(record, "brand_name");
        genericName = string(record, "generic_name");
        if (brandName.isEmpty() && genericName.isEmpty() && productNdc.isEmpty()) {
            throw new JSONException("the medicine has no name or product code");
        }
        labeler = string(record, "labeler_name");
        form = string(record, "dosage_form");
        route = joined(record.optJSONArray("route"));
        type = type(string(record, "product_type"));
        marketing = string(record, "marketing_category");
        schedule = string(record, "dea_schedule");
        fdaDate = date(string(medicine, "fda_updated"));

        labelRead = label != null;
        JSONObject read = labelRead ? label : new JSONObject();
        purpose = afterHeading(string(read, "purpose"), "purposes", "purpose");
        indication = indication(string(read, "indications_and_usage"));
        String warning = string(read, "boxed_warning");
        boxedWarning = !warning.isEmpty();
        boxedWarningTitle = headline(warning);

        ingredients = Collections.unmodifiableList(ingredients(record.optJSONArray("active_ingredients")));
        classes = Collections.unmodifiableList(classes(object(record, "openfda")));
        JSONArray packaging = record.optJSONArray("packaging");
        packageSizes = packaging == null ? 0 : packaging.length();
        packageLines = Collections.unmodifiableList(packageLines(packaging, packageNdc));
    }

    /** The brand name, or the generic name of a medicine sold without one. */
    String name() {
        return brandName.isEmpty() ? genericName : brandName;
    }

    /** The strength, when there is one ingredient to give it for; several are listed on their own card. */
    String strength() {
        return ingredients.size() == 1 ? ingredients.get(0).strength : "";
    }

    /** The code of the package scanned, or of the product if the package isn't known. */
    String ndc() {
        return packageNdc.isEmpty() ? productNdc : packageNdc;
    }

    /**
     * One line on what it is for: an over-the-counter label's own words, else the kind of
     * drug it is, else what a prescription label says it is indicated for. Empty if none is known.
     */
    String use() {
        if (!purpose.isEmpty()) {
            return purpose;
        }
        return classes.isEmpty() ? indication : classes.get(0).name;
    }

    // ---------------------------------------------------------------- sections

    private static String type(String productType) {
        String type = productType.toUpperCase(Locale.US);
        if (type.contains("OTC")) {
            return "OTC";
        }
        if (type.contains("PRESCRIPTION")) {
            return "RX";
        }
        return type.startsWith("HUMAN ") ? type.substring("HUMAN ".length()) : type;
    }

    private static List<Ingredient> ingredients(JSONArray entries) {
        List<Ingredient> list = new ArrayList<>();
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && !string(entry, "name").isEmpty()) {
                // "200 mg/1" is 200 mg in each tablet: the "/1" says nothing.
                String strength = string(entry, "strength").toUpperCase(Locale.US);
                if (strength.endsWith("/1")) {
                    strength = strength.substring(0, strength.length() - 2);
                }
                list.add(new Ingredient(string(entry, "name"), strength.trim()));
            }
        }
        return list;
    }

    /** What the medicine is first, then how it works, what it does and what it is made of. */
    private static List<DrugClass> classes(JSONObject openfda) {
        String[][] kinds = {{"pharm_class_epc", "CLASS"}, {"pharm_class_moa", "ACTION"},
                {"pharm_class_pe", "EFFECT"}, {"pharm_class_cs", "CHEMISTRY"}};
        List<DrugClass> list = new ArrayList<>();
        for (String[] kind : kinds) {
            JSONArray names = openfda.optJSONArray(kind[0]);
            for (int i = 0; names != null && i < names.length(); i++) {
                // Each ends in its kind in brackets, as in "Cyclooxygenase Inhibitors [MoA]".
                String name = names.optString(i, "").replaceAll("\\s*\\[[^\\]]*\\]\\s*$", "").trim();
                if (!name.isEmpty()) {
                    list.add(new DrugClass(name, kind[1]));
                }
            }
        }
        return list;
    }

    /**
     * The scanned package's description, one line for each level of packaging.
     * "1 BOTTLE in 1 CARTON (0573-0150-40)  / 100 TABLET, COATED in 1 BOTTLE" is two lines.
     */
    private static List<String> packageLines(JSONArray packaging, String packageNdc) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; packaging != null && i < packaging.length(); i++) {
            JSONObject entry = packaging.optJSONObject(i);
            if (entry == null || packageNdc.isEmpty() || !packageNdc.equals(string(entry, "package_ndc"))) {
                continue;
            }
            for (String level : string(entry, "description").split("\\s+/\\s+")) {
                String line = level.replaceAll("\\s*\\([^)]*\\)", "").trim().toUpperCase(Locale.US);
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            break;
        }
        return lines;
    }

    /**
     * A boxed warning's headline: the capitals it opens with, without the word "WARNING".
     * "WARNING: BLEEDING RISK Warfarin sodium can cause ..." gives "BLEEDING RISK".
     */
    private static String headline(String warning) {
        StringBuilder headline = new StringBuilder();
        for (String word : warning.trim().split("\\s+")) {
            if (word.isEmpty() || !word.equals(word.toUpperCase(Locale.US)) || !word.matches(".*[A-Z].*")) {
                break;   // the sentence under the headline has begun
            }
            headline.append(headline.length() == 0 ? "" : " ").append(word);
        }
        String text = headline.toString();
        // The sentence often opens with a one-letter word, which reads as part of the capitals.
        text = text.replaceAll("\\s+[A-Z]$", "");
        text = text.replaceAll("^(BOXED\\s+)?WARNINGS?\\b[:\\s]*", "").replaceAll("^(BOXED\\s+)?WARNINGS?\\b[:\\s]*", "");
        return text.replaceAll("[\\s:.,;-]+$", "").trim();
    }

    /**
     * What follows "indicated for" in a label's indications, up to the end of the clause.
     * "... tablets are indicated for: Prophylaxis and treatment of venous thrombosis and its
     * extension, pulmonary embolism (PE). ..." gives "Prophylaxis and treatment of venous
     * thrombosis and its extension, pulmonary embolism".
     */
    private static String indication(String indications) {
        String lower = indications.toLowerCase(Locale.US);
        int at = -1;
        int length = 0;
        for (String opening : new String[] {"indicated for", "indicated in", "indicated to", "indicated as"}) {
            int found = lower.indexOf(opening);
            if (found >= 0 && (at < 0 || found < at)) {
                at = found;
                length = opening.length();
            }
        }
        if (at < 0) {
            return "";
        }
        String rest = indications.substring(at + length).replaceAll("^[\\s:]+", "");
        rest = rest.replaceAll("^(?i)(the|use in the|use in)\\s+", "");
        return rest.split("[.;:(\\[]", 2)[0].trim();
    }

    /** A label section without the heading it opens with: "Purpose Pain reliever" gives "Pain reliever". */
    private static String afterHeading(String text, String... headings) {
        String rest = text.trim();
        for (String heading : headings) {
            if (rest.toLowerCase(Locale.US).startsWith(heading)) {
                return rest.substring(heading.length()).replaceAll("^[\\s:]+", "");
            }
        }
        return rest;
    }

    // ---------------------------------------------------------------- reading JSON

    /** "2026-11-20" as "11/20/2026"; anything else as it is. */
    private static String date(String day) {
        String[] parts = day.split("-");
        return parts.length == 3 ? parts[1] + "/" + parts[2] + "/" + parts[0] : day;
    }

    private static JSONObject object(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        return child != null ? child : new JSONObject();
    }

    /** The text under the key, or empty if it is missing or null. */
    private static String string(JSONObject parent, String key) {
        return parent.isNull(key) ? "" : parent.optString(key, "").trim();
    }

    private static String joined(JSONArray values) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; values != null && i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (!value.isEmpty()) {
                joined.append(joined.length() == 0 ? "" : ", ").append(value);
            }
        }
        return joined.toString();
    }
}
