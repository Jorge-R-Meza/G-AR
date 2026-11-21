package com.hackathon.glasseshello;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * A patient report, as the link in a scanned QR code returns it: a summary of the patient's
 * FHIR record. Nothing here is fixed to one patient; every field is read from the report,
 * and a field the report leaves out comes through empty.
 *
 * <pre>
 * {
 *   "patient_id": "...",
 *   "glasses_hud": {
 *     "alerts": ["HIGH BP 145/72", ...], "bp": "145/72", "a1c": "7.4%",
 *     "conditions": ["..."], "meds": ["..."]
 *   },
 *   "phone_detail": {
 *     "demographics": {"gender": "male", "birth_date": "1952-05-13", "age_on_...": 74},
 *     "conditions": [{"display": "...", "icd10_code": "E11", "onset_date": "2018-06-30"}],
 *     "active_medications": [{"medication": "...", "authored_on": "2025-07-07"}],
 *     "latest_observations": {"ldl_mg_dl": {"value": 67.0, "unit": "mg/dL", "date": "..."}, ...}
 *   }
 * }
 * </pre>
 */
final class PatientReport {

    static final class Condition {
        final String name;
        /** ICD-10 code, such as "E11". */
        final String code;
        /** Year of onset, such as "2018". */
        final String onset;

        Condition(String name, String code, String onset) {
            this.name = name;
            this.code = code;
            this.onset = onset;
        }
    }

    static final class Medication {
        final String name;
        /** When it was prescribed, as "07/2025". */
        final String ordered;

        Medication(String name, String ordered) {
            this.name = name;
            this.ordered = ordered;
        }
    }

    static final class Observation {
        /** Such as "FASTING GLUCOSE". */
        final String label;
        /** Such as "172" or, for blood pressure, "145/72". */
        final String value;
        final String unit;
        /** As "06/25/2026". */
        final String date;

        Observation(String label, String value, String unit, String date) {
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.date = date;
        }
    }

    /** The report exactly as it arrived, to tell a new report from one already showing. */
    final String source;
    final String patientId;
    /** "M", "F", or the report's own word in capitals. */
    final String sex;
    /** In years, or empty. */
    final String age;
    /** As "05/13/1952". */
    final String birthDate;
    final List<String> alerts;
    /** Such as "145/72". */
    final String bloodPressure;
    /** Such as "7.4%". */
    final String a1c;
    final List<Condition> conditions;
    final List<Medication> medications;
    final List<Observation> observations;
    /** The day of the most recent observation, as "06/25/2026", or empty. */
    final String observationsDate;

    /** Throws if the text is not a JSON object, or has no patient ID to show it under. */
    static PatientReport parse(String json) throws JSONException {
        return new PatientReport(json);
    }

    private PatientReport(String json) throws JSONException {
        source = json.trim();
        JSONObject root = new JSONObject(source);
        JSONObject hud = object(root, "glasses_hud");
        JSONObject detail = object(root, "phone_detail");
        JSONObject who = object(detail, "demographics");

        patientId = first(string(root, "patient_id"), string(who, "patient_id"));
        if (patientId.isEmpty()) {
            throw new JSONException("the report has no patient_id");
        }
        sex = sex(string(who, "gender"));
        age = age(who);
        birthDate = date(string(who, "birth_date"));
        alerts = Collections.unmodifiableList(strings(hud.optJSONArray("alerts")));
        conditions = Collections.unmodifiableList(conditions(detail, hud));
        medications = Collections.unmodifiableList(medications(detail, hud));

        JSONObject readings = object(detail, "latest_observations");
        observations = Collections.unmodifiableList(observations(readings));
        observationsDate = date(latestDate(readings));
        bloodPressure = first(string(hud, "bp"), bloodPressure(readings));
        a1c = first(string(hud, "a1c"), a1c(readings));
    }

    // ---------------------------------------------------------------- sections

    private static String sex(String gender) {
        switch (gender.toLowerCase(Locale.US)) {
            case "male": return "M";
            case "female": return "F";
            default: return gender.toUpperCase(Locale.US);
        }
    }

    /** The age the report gives ("age", "age_on_2026_06_30", ...), or else worked out from the birth date. */
    private static String age(JSONObject who) {
        for (Iterator<String> keys = who.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (key.toLowerCase(Locale.US).startsWith("age") && who.opt(key) instanceof Number) {
                return String.valueOf(who.optInt(key));
            }
        }
        try {
            LocalDate born = LocalDate.parse(string(who, "birth_date"));
            return String.valueOf(Period.between(born, LocalDate.now()).getYears());
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The detailed list, or the short list of names made for the display if that is all there is. */
    private static List<Condition> conditions(JSONObject detail, JSONObject hud) {
        List<Condition> list = new ArrayList<>();
        JSONArray entries = detail.optJSONArray("conditions");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && !string(entry, "display").isEmpty()) {
                list.add(new Condition(string(entry, "display"), string(entry, "icd10_code"),
                        year(string(entry, "onset_date"))));
            }
        }
        if (list.isEmpty()) {
            for (String name : strings(hud.optJSONArray("conditions"))) {
                list.add(new Condition(name, "", ""));
            }
        }
        return list;
    }

    private static List<Medication> medications(JSONObject detail, JSONObject hud) {
        List<Medication> list = new ArrayList<>();
        JSONArray entries = detail.optJSONArray("active_medications");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && !string(entry, "medication").isEmpty()) {
                list.add(new Medication(string(entry, "medication"),
                        monthAndYear(string(entry, "authored_on"))));
            }
        }
        if (list.isEmpty()) {
            for (String name : strings(hud.optJSONArray("meds"))) {
                list.add(new Medication(name, ""));
            }
        }
        return list;
    }

    /**
     * One line per reading, in the report's order. Systolic and diastolic pressure are two
     * readings in FHIR; they become the one "BP 145/72" line a clinician expects.
     */
    private static List<Observation> observations(JSONObject readings) {
        List<Observation> list = new ArrayList<>();
        for (Iterator<String> keys = readings.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject reading = readings.optJSONObject(key);
            if (reading == null || reading.isNull("value")) {
                continue;
            }
            String unit = unit(string(reading, "unit"));
            String date = date(string(reading, "date"));
            if (key.startsWith("systolic")) {
                JSONObject other = readings.optJSONObject("diastolic" + key.substring("systolic".length()));
                if (other != null && !other.isNull("value")) {
                    list.add(new Observation("BP", number(reading) + "/" + number(other), unit, date));
                    continue;
                }
            } else if (key.startsWith("diastolic")
                    && readings.optJSONObject("systolic" + key.substring("diastolic".length())) != null) {
                continue;   // shown with the systolic reading
            }
            list.add(new Observation(label(key, string(reading, "unit")), number(reading), unit, date));
        }
        return list;
    }

    /** The most recent day any reading was taken, as the report writes it: "2026-06-25". */
    private static String latestDate(JSONObject readings) {
        String latest = "";
        for (Iterator<String> keys = readings.keys(); keys.hasNext(); ) {
            JSONObject reading = readings.optJSONObject(keys.next());
            String day = reading == null ? "" : string(reading, "date");
            // Dates written year first sort as text.
            if (isIsoDate(day) && day.substring(0, 10).compareTo(latest) > 0) {
                latest = day.substring(0, 10);
            }
        }
        return latest;
    }

    private static String bloodPressure(JSONObject readings) {
        JSONObject systolic = readings.optJSONObject("systolic_bp");
        JSONObject diastolic = readings.optJSONObject("diastolic_bp");
        if (systolic == null || diastolic == null || systolic.isNull("value") || diastolic.isNull("value")) {
            return "";
        }
        return number(systolic) + "/" + number(diastolic);
    }

    private static String a1c(JSONObject readings) {
        for (Iterator<String> keys = readings.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject reading = readings.optJSONObject(key);
            if (key.toLowerCase(Locale.US).contains("a1c") && reading != null && !reading.isNull("value")) {
                return number(reading) + string(reading, "unit");
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- wording

    /**
     * A reading's name from its key: "fasting_glucose_mg_dl" in mg/dL is "FASTING GLUCOSE".
     * Words at the end that only repeat the unit are dropped.
     */
    static String label(String key, String unit) {
        String[] words = key.split("_");
        String unitLetters = unit.replace("%", "percent").replaceAll("[^A-Za-z0-9]", "");
        int end = words.length;
        for (int start = 1; start < words.length; start++) {
            StringBuilder tail = new StringBuilder();
            for (int i = start; i < words.length; i++) {
                tail.append(words[i]);
            }
            if (tail.toString().equalsIgnoreCase(unitLetters)) {
                end = start;
                break;
            }
        }
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < end; i++) {
            label.append(i == 0 ? "" : " ").append(words[i]);
        }
        return label.toString().toUpperCase(Locale.US);
    }

    /** A UCUM unit the way it is usually written: "mm[Hg]" is "mmHg", "mL/min/{1.73_m2}" is "mL/min/1.73m2". */
    static String unit(String ucum) {
        return ucum.replaceAll("[\\[\\]{}_]", "");
    }

    /** A reading's value without a pointless ".0": 41.0 is "41", 7.4 stays "7.4". */
    private static String number(JSONObject reading) {
        Object value = reading.opt("value");
        if (value instanceof Number) {
            return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US))
                    .format(((Number) value).doubleValue());
        }
        return String.valueOf(value);
    }

    /** "1952-05-13" as "05/13/1952". Anything else is left as it is. */
    static String date(String iso) {
        return isIsoDate(iso) ? iso.substring(5, 7) + "/" + iso.substring(8, 10) + "/" + iso.substring(0, 4) : iso;
    }

    /** "2025-07-07" as "07/2025". */
    private static String monthAndYear(String iso) {
        return isIsoDate(iso) ? iso.substring(5, 7) + "/" + iso.substring(0, 4) : iso;
    }

    private static String year(String iso) {
        return isIsoDate(iso) ? iso.substring(0, 4) : iso;
    }

    /** Whether the text starts with a date such as "2026-06-25"; a time may follow it. */
    private static boolean isIsoDate(String text) {
        return text.matches("\\d{4}-\\d{2}-\\d{2}.*");
    }

    // ---------------------------------------------------------------- JSON

    private static JSONObject object(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        return child != null ? child : new JSONObject();
    }

    /** The value as text, or empty if it is missing or null. */
    private static String string(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key).trim();
    }

    private static List<String> strings(JSONArray array) {
        List<String> list = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            if (!array.isNull(i) && !array.optString(i).trim().isEmpty()) {
                list.add(array.optString(i).trim());
            }
        }
        return list;
    }

    private static String first(String preferred, String fallback) {
        return preferred.isEmpty() ? fallback : preferred;
    }
}
