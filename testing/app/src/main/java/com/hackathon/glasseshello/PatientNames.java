package com.hackathon.glasseshello;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Random;

/**
 * Made-up names for patients. A report carries an ID but no name, so each patient is given
 * a random name that suits their sex, and keeps it: the name is stored against the patient
 * ID, so the same patient has the same name every time they are scanned. Exiting the app
 * forgets the names along with everything else that was scanned (see MainActivity.finish).
 */
final class PatientNames {

    private static final String[] MALE = {
            "James", "Robert", "Michael", "David", "William", "Daniel", "Thomas", "Carlos",
            "Andrew", "Samuel", "George", "Henry", "Marcus", "Peter", "Victor", "Luis"};
    private static final String[] FEMALE = {
            "Mary", "Patricia", "Linda", "Elizabeth", "Susan", "Jessica", "Sarah", "Karen",
            "Maria", "Laura", "Helen", "Grace", "Alice", "Rosa", "Julia", "Emma"};
    /** For a patient whose sex the report doesn't give, or gives as something else. */
    private static final String[] EITHER = {
            "Alex", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Jamie", "Robin"};
    private static final String[] LAST = {
            "Carter", "Bennett", "Hughes", "Morales", "Sullivan", "Foster", "Nguyen", "Ramirez",
            "Coleman", "Brooks", "Patel", "Reyes", "Fisher", "Walsh", "Jensen", "Ortiz"};

    private PatientNames() {
    }

    /**
     * The patient's name: the one already given to this ID, or else a new random one.
     *
     * @param sex "M", "F" or anything else, as in {@link PatientReport#sex}
     */
    static String nameFor(Context context, String patientId, String sex) {
        SharedPreferences names = context.getSharedPreferences("patient_names", Context.MODE_PRIVATE);
        String name = names.getString(patientId, null);
        if (name == null) {
            Random random = new Random();
            String[] first = "M".equals(sex) ? MALE : "F".equals(sex) ? FEMALE : EITHER;
            name = first[random.nextInt(first.length)] + " " + LAST[random.nextInt(LAST.length)];
            names.edit().putString(patientId, name).apply();
        }
        return name;
    }

    /** Forgets every name given so far: a patient scanned again gets a new one. */
    static void forgetAll(Context context) {
        context.getSharedPreferences("patient_names", Context.MODE_PRIVATE).edit().clear().apply();
    }
}
