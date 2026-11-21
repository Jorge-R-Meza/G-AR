package com.hackathon.glasseshello;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The cards app for the M6 glasses (640x480, Android 9), using plain Android APIs only.
 *
 * It began as a hardware probe, whose screen was the first card. That screen is gone, and so
 * is the microphone test that only fed it; both are in the git history.
 *
 * The screen is a small deck of cards, moved through with head flicks. A card is a rounded
 * outline in the middle of the display with arrows around it:
 *   - flick left or right: move to the next card that way;
 *   - flick down: hide the card, leaving an up arrow at the bottom of the display;
 *   - flick up: bring the card back.
 * A flick is a short, fast burst of head rotation; see the constants below for the numbers.
 * The three cards show a patient, drawn in green only: a summary (who they are, alerts, blood
 * pressure and A1C), then their conditions and medications, then their latest results.
 * Nothing about the patient is built in, except that the report has no name, so the summary
 * shows a made-up one that stays with the patient ID (see PatientNames). The laptop's stream viewer reads the QR code in the
 * camera stream, fetches the report the code links to and sends it here (see ReportServer);
 * the cards are filled from that report, and say "NO PATIENT" until one arrives.
 * The cards start hidden, leaving the view clear. When a report arrives, "REPORT READY"
 * shows at the bottom of the display and, a moment later, the patient's summary opens.
 * The same three cards show a medicine instead, when the viewer reads the barcode on a package
 * and sends what the FDA lists for it (see Medicine): what it is, then its ingredients and
 * drug class, then its package. "MEDICINE READY" shows first, as for a report. There is one
 * thing on the cards at a time: a medicine takes the patient's place, and a patient the
 * medicine's.
 * To send a report by hand:
 *   adb forward tcp:32889 tcp:32889 && nc localhost 32889 < report.json
 * The arrow keys do the same, for testing without wearing the glasses:
 *   adb shell input keyevent DPAD_LEFT      (or DPAD_RIGHT, DPAD_DOWN, DPAD_UP)
 *
 * A double tap on the touchpad (it arrives as the F12 key) exits the app, back to the
 * glasses' menu. Exiting forgets everything scanning put in the app: the patient on the
 * cards and the names made up for patients. The next time it opens, it says "NO PATIENT".
 * The app is opened again over Wi-Fi (see ControlServer), and starts with the glasses once
 * setup.sh has been run (see OpenService).
 *
 * Black pixels are see-through on the waveguide, so the screen is black with bright text.
 * Everything shown is also logged once a second:  adb logcat -s GlassesProbe
 *
 * Flick test: tap the touchpad (or send any key with "adb shell input keyevent") to start it.
 * It records normal head movement for a few seconds, then prompts for flicks in each direction
 * and shows the peak angular speed of each one and whether the detector caught it. The cards
 * stay put while it runs. Results are appended to flick_trials_v2.csv and flick_samples.csv
 * in the app's external files folder:
 *   adb pull /sdcard/Android/data/com.hackathon.glasseshello/files/
 *
 * The app never opens the camera. Only one app can have it at a time, and the glasses' own
 * stream service needs it to send the video the QR codes are read from: while this app held
 * the camera, the stream could not start.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "GlassesProbe";

    // Flick directions. Opposites differ only in the lowest bit.
    private static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;
    private static final String[] DIRECTION_NAMES = {"LEFT", "RIGHT", "UP", "DOWN"};

    /*
     * Flick detection. The numbers come from the prompted flick test on 2026-11-21 (two runs,
     * 24 flicks, one wearer, gyroscope at about 99 Hz):
     *   - worn, the sensor's x axis is vertical and its y axis runs ear to ear: a turn to the
     *     left is +x, to the right -x, looking up is -y and down +y; z is tilting the head;
     *   - firm flicks peaked at 2.6 to 5.2 rad/s, subtle ones at 1.5 to 2.0 sideways and 1.0
     *     to 1.4 up or down; ordinary movement reached 1.0 sideways and 0.6 up or down;
     *   - a flick is a short burst, 51 to 162 ms, where ordinary movement that fast lasted
     *     223 to 325 ms. Burst length is what tells them apart: both swing back afterwards.
     */
    /** Angular speed, in rad/s, that a turn to the left or right must reach to count as a flick. */
    private static final float YAW_THRESHOLD = 1.3f;
    /** The same for looking up or down, which is naturally a smaller movement. */
    private static final float PITCH_THRESHOLD = 0.8f;
    /**
     * Looking up has its own, lower bar: the wearer asked for up flicks to be a little easier.
     * Ordinary up and down movement reached about 0.6 rad/s, so this leaves little margin.
     */
    private static final float UP_THRESHOLD = 0.65f;
    /** A burst starts when the speed rises above this and ends when it falls back below. */
    private static final float BURST_ONSET = 0.4f;
    /** A burst longer than this is an ordinary head movement, not a flick. */
    private static final long BURST_MAX_MS = 200;
    /** Up flicks came out slower than the others (many ran 230 ms or more), so they get longer. */
    private static final long UP_BURST_MAX_MS = 260;
    /** A burst shorter than this is a jolt (one or two samples); real flicks ran 51 ms or more. */
    private static final long BURST_MIN_MS = 30;
    /**
     * An up or down flick is ignored if the head was turning sideways at least this fast,
     * in rad/s: sideways flicks arc, and were setting off "down". In the prompted test, pure
     * up and down flicks stayed under 0.5 sideways.
     */
    private static final float SIDEWAYS_LIMIT = 0.8f;
    /** A sideways flick is ignored only if the up or down speed was this many times faster. */
    private static final float VERTICAL_RATIO = 2.0f;
    private static final long FLICK_COOLDOWN_MS = 400;
    /** A flick the opposite way this soon after another is the head swinging back... */
    private static final long FLICK_RETURN_MS = 700;
    /** ...unless it is this many times faster: then the first was a wind-up and this is the flick. */
    private static final float WINDUP_RATIO = 1.5f;

    /** The three cards show what was last scanned: a patient or a medicine. */
    private static final int CARD_COUNT = 3;
    private static final int SUMMARY_CARD = 0;
    private static final int CARD_WIDTH = 520, CARD_HEIGHT = 372;
    /** How long "REPORT READY" shows before the report opens. */
    private static final long REPORT_READY_MS = 1500;
    /*
     * The cards cannot scroll, so each list is cut to the lines that fit and ends in
     * "+N MORE" when the report has more. These are how many lines fit at 22 px.
     */
    /** Alerts on the summary card. */
    private static final int ALERT_ROWS = 3;
    /** Conditions and medications together, under their two headings. */
    private static final int LIST_ROWS = 10;
    /** Results, under their heading. */
    private static final int RESULT_ROWS = 11;
    /**
     * Text size inside the card, always in bold. Smaller or regular-weight text looks soft
     * through the lens, so the card is sized to fit this rather than the other way round.
     */
    private static final int PROBE_TEXT_SP = 22;
    /** Arrows are triangles: this long across the base and this deep to the point. */
    private static final int ARROW_BASE = 48, ARROW_DEPTH = 30;

    private final Handler ui = new Handler(Looper.getMainLooper());

    // Cards
    private FrameLayout card;
    /** The summary, the conditions and medications, and the results, in that order. */
    private final LinearLayout[] patientPanels = new LinearLayout[CARD_COUNT - SUMMARY_CARD];
    /** The patient on the cards, or null until a report arrives. */
    private PatientReport patient;
    /** The medicine on the cards instead of a patient, or null. At most one of the two is set. */
    private Medicine medicine;
    private ArrowView leftArrow, rightArrow, downArrow, upArrow;
    private int cardIndex = SUMMARY_CARD;
    /** The cards start out of the way; a flick up, or a report arriving, brings one into view. */
    private boolean cardHidden = true;
    /** Shown at the bottom of the display, where the up arrow goes, while a new report waits to open. */
    private TextView readyLabel;
    private boolean reportReady;

    // Sensors
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private String sensorSummary = "";
    private final float[] gyro = new float[3];
    private int gyroEvents;
    private float gyroHz;
    private final Burst yawBurst = new Burst();
    private final Burst pitchBurst = new Burst();
    private int lastFlick = -1;
    private long lastFlickAt;
    private float lastFlickPeak;
    private boolean lastFlickMoved;
    private String flickStatus = "turn your head quickly";

    private String displaySummary = "";
    private String gmsStatus = "";
    private String networkStatus = "";
    private String lastInput = FLICK_TEST_ENABLED ? "tap = start flick test" : "tap the touchpad";
    private long windowStart;

    // Flick test
    /**
     * Off for now: any touchpad or ring key starts the test, so it kept starting by accident.
     * Set to true to bring back "tap to start".
     */
    private static final boolean FLICK_TEST_ENABLED = false;
    private static final String[] FLICK_DIRECTIONS = {"LEFT", "RIGHT", "UP", "DOWN"};
    private static final int FLICK_REPEATS = 3;
    private static final int FLICK_TOTAL = FLICK_DIRECTIONS.length * FLICK_REPEATS;
    private static final long NORMAL_MS = 8000;   // ordinary movement, the floor for a threshold
    private static final long READY_MS = 1500;    // shows the next direction
    private static final long GO_MS = 2000;       // the flick is recorded during this window
    private static final long TAP_DEBOUNCE_MS = 500;

    private static final int TEST_OFF = 0;
    private static final int TEST_NORMAL = 1;
    private static final int TEST_READY = 2;
    private static final int TEST_GO = 3;
    private static final int TEST_RESULTS = 4;

    private LinearLayout testPanel;
    private TextView testBig, testSub, testResults;
    private int testState = TEST_OFF;
    private int testTrial;                 // which flick is being prompted, from 0
    private long testPhaseStart;
    private long testPhaseLength;
    private String testRunId = "";
    private final List<float[]> testSamples = new ArrayList<>();   // {ms into the phase, x, y, z}
    private final List<Integer> testDetected = new ArrayList<>();  // flicks the detector reported
    private Trial normalTrial;
    private final List<Trial> testTrials = new ArrayList<>();
    private String lastTrialSummary = "";
    private long lastTapAt;
    private long lastGyroEventAt;

    // ---------------------------------------------------------------- open and closed

    /** The open app: on screen or behind another app. Null once it has been exited. */
    private static volatile MainActivity open;
    private static volatile boolean visible;

    /** Whether the app is on screen, rather than closed or behind another app. */
    static boolean isVisible() {
        return visible;
    }

    /** Exits the app, as a double tap does. Does nothing if it isn't open. From any thread. */
    static void close() {
        MainActivity activity = open;
        if (activity != null) {
            activity.runOnUiThread(activity::finish);
        }
    }

    /**
     * Puts a report from the laptop on the cards. From any thread.
     * Returns false, and drops the report, if the app is not open.
     */
    static boolean deliver(PatientReport report) {
        MainActivity activity = open;
        if (activity == null) {
            return false;
        }
        activity.ui.post(() -> activity.showPatient(report));
        return true;
    }

    /** The same for a medicine the laptop looked up from a barcode. */
    static boolean deliver(Medicine medicine) {
        MainActivity activity = open;
        if (activity == null) {
            return false;
        }
        activity.ui.post(() -> activity.showMedicine(medicine));
        return true;
    }

    /**
     * Exiting the app (a double tap, or /close over Wi-Fi) forgets everything scanning put
     * in it, so the next time it opens there is no patient and no name left from before.
     * From here on reports are turned away until the app is opened again: a code still in
     * front of the camera is sent every few seconds, and would otherwise bring it all back.
     */
    @Override
    public void finish() {
        if (open == this) {
            open = null;
        }
        ui.removeCallbacks(openReport);
        patient = null;
        medicine = null;
        PatientNames.forgetAll(this);
        Log.i(TAG, "app exited: scanned data forgotten");
        super.finish();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        open = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        displaySummary = metrics.widthPixels + "x" + metrics.heightPixels + " @ " + metrics.densityDpi + " dpi";
        gmsStatus = hasPackage("com.google.android.gms") ? "present" : "absent";
        Log.i(TAG, "display " + displaySummary + ", Google Play Services " + gmsStatus);

        probeSensors();

        // The service takes the reports the laptop sends and hands them to deliver(). It
        // outlives this screen, so they still arrive while another app is in front: the
        // camera stream that the code is read from may come from one.
        startForegroundService(new Intent(this, KeepAliveService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        yawBurst.sign = 0;
        pitchBurst.sign = 0;
        windowStart = SystemClock.elapsedRealtime();
        gyroEvents = 0;
        ui.post(tick);
        visible = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        ui.removeCallbacks(tick);
        endFlickTest();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (open == this) {
            open = null;   // closed by Android rather than exited: nothing is forgotten
        }
    }

    // ---------------------------------------------------------------- screen

    private void buildScreen() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        // Centred on the display. The glasses' launcher draws its status bar over the top
        // 36 px of every app, and the card starts well below that.
        int cardLeft = (width - CARD_WIDTH) / 2;
        int cardTop = (height - CARD_HEIGHT) / 2;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.BLACK);
        outline.setStroke(3, Color.GREEN);
        outline.setCornerRadius(24);
        card = new FrameLayout(this);
        card.setBackground(outline);
        card.setPadding(14, 8, 14, 8);
        root.addView(card, at(cardLeft, cardTop, CARD_WIDTH, CARD_HEIGHT));

        // The flick test takes over the card while it runs.
        testPanel = new LinearLayout(this);
        testPanel.setOrientation(LinearLayout.VERTICAL);
        testBig = label("", Color.GREEN);
        testBig.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        testBig.setGravity(Gravity.CENTER_HORIZONTAL);
        testPanel.addView(testBig, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        testSub = label("", Color.GREEN);
        testSub.setGravity(Gravity.CENTER_HORIZONTAL);
        testPanel.addView(testSub, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        testResults = label("", Color.GREEN);
        testResults.setPadding(0, 8, 0, 0);
        testPanel.addView(testResults);
        card.addView(testPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int i = 0; i < patientPanels.length; i++) {
            patientPanels[i] = new LinearLayout(this);
            patientPanels[i].setOrientation(LinearLayout.VERTICAL);
            card.addView(patientPanels[i], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
        fillPatientCards();

        // Left and right arrows sit halfway between the card and the edge of the display.
        int sideGap = (cardLeft - ARROW_DEPTH) / 2;
        int sideTop = cardTop + (CARD_HEIGHT - ARROW_BASE) / 2;
        int middle = (width - ARROW_BASE) / 2;
        leftArrow = new ArrowView(this, LEFT);
        root.addView(leftArrow, at(sideGap, sideTop, ARROW_DEPTH, ARROW_BASE));
        rightArrow = new ArrowView(this, RIGHT);
        root.addView(rightArrow,
                at(cardLeft + CARD_WIDTH + sideGap, sideTop, ARROW_DEPTH, ARROW_BASE));
        downArrow = new ArrowView(this, DOWN);
        root.addView(downArrow, at(middle, cardTop + CARD_HEIGHT + 14, ARROW_BASE, ARROW_DEPTH));
        // Shown only while the card is hidden.
        upArrow = new ArrowView(this, UP);
        root.addView(upArrow, at(middle, height - ARROW_DEPTH - 16, ARROW_BASE, ARROW_DEPTH));
        readyLabel = text("REPORT READY", 28, Color.GREEN);
        readyLabel.setGravity(Gravity.CENTER);
        root.addView(readyLabel, at(0, height - ARROW_DEPTH - 26, width, 40));

        setContentView(root);
        showCard();
    }

    private static FrameLayout.LayoutParams at(int left, int top, int width, int height) {
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(width, height, Gravity.TOP | Gravity.LEFT);
        params.leftMargin = left;
        params.topMargin = top;
        return params;
    }

    private TextView label(String text, int colour) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(colour);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, PROBE_TEXT_SP);
        return view;
    }

    // ---------------------------------------------------------------- cards

    /** Shows the current card with its arrows, or only the up arrow while the card is hidden. */
    private void showCard() {
        boolean testing = testState != TEST_OFF;
        card.setVisibility(cardHidden ? View.INVISIBLE : View.VISIBLE);
        // No arrows during the flick test: flicks are being measured, not acted on.
        boolean arrows = !cardHidden && !testing;
        // The deck does not wrap round: an arrow only shows where there is a card to go to.
        leftArrow.setVisibility(arrows && cardIndex > 0 ? View.VISIBLE : View.INVISIBLE);
        rightArrow.setVisibility(arrows && cardIndex < CARD_COUNT - 1 ? View.VISIBLE : View.INVISIBLE);
        downArrow.setVisibility(arrows ? View.VISIBLE : View.INVISIBLE);
        upArrow.setVisibility(cardHidden && !reportReady ? View.VISIBLE : View.INVISIBLE);
        readyLabel.setVisibility(cardHidden && reportReady ? View.VISIBLE : View.INVISIBLE);

        testPanel.setVisibility(testing ? View.VISIBLE : View.INVISIBLE);
        for (int i = 0; i < patientPanels.length; i++) {
            patientPanels[i].setVisibility(
                    !testing && cardIndex == SUMMARY_CARD + i ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ---------------------------------------------------------------- patient cards

    /*
     * The display shows green only, and black is see-through, so these cards have no colour
     * to lean on. Importance comes from size and from solid blocks (the alerts), whose text
     * is black, meaning cut out of the block. Everything is bold and at least 22 px, and
     * lines are 2 to 3 px thick, because anything finer looks soft through the lens.
     */

    /**
     * Puts a newly received patient on the cards. Whatever was showing is put away and
     * "REPORT READY" appears at the bottom of the display; then the summary opens by itself.
     */
    private void showPatient(PatientReport report) {
        if (isFinishing()) {
            return;   // sent just as the app was exited
        }
        // A code that stays in front of the camera is sent again every few seconds. Only a
        // report that differs from the one on the cards is news: the same one again must not
        // pull the wearer back from the card they have moved to, nor bring back cards they
        // have put away. Those stay behind the up arrow until a flick up.
        if (patient != null && patient.source.equals(report.source)) {
            return;
        }
        patient = report;
        medicine = null;
        announce("REPORT READY");
    }

    /**
     * Puts a newly received medicine on the cards in the patient's place, the same way:
     * "MEDICINE READY" at the bottom of the display, then its summary opens by itself.
     */
    private void showMedicine(Medicine scanned) {
        if (isFinishing()) {
            return;   // sent just as the app was exited
        }
        // As for a patient: a barcode still in view is sent again, and the same medicine
        // again is not news.
        if (medicine != null && medicine.source.equals(scanned.source)) {
            return;
        }
        medicine = scanned;
        patient = null;
        announce("MEDICINE READY");
    }

    /** Fills the cards with what has just arrived, says so, and opens its summary a moment later. */
    private void announce(String ready) {
        fillPatientCards();
        if (testState != TEST_OFF) {
            return;   // the flick test has the screen; the cards are filled when it ends
        }
        readyLabel.setText(ready);
        cardIndex = SUMMARY_CARD;
        cardHidden = true;
        reportReady = true;
        showCard();
        ui.removeCallbacks(openReport);
        ui.postDelayed(openReport, REPORT_READY_MS);
    }

    /** Opens the report that "REPORT READY" announced, unless a flick up already has. */
    private final Runnable openReport = new Runnable() {
        @Override
        public void run() {
            if (reportReady) {
                reportReady = false;
                cardHidden = false;
                Log.i(TAG, "report opened: card " + (cardIndex + 1) + " of " + CARD_COUNT);
                showCard();
            }
        }
    };

    private void fillPatientCards() {
        for (LinearLayout panel : patientPanels) {
            panel.removeAllViews();
            panel.setGravity(patient == null && medicine == null ? Gravity.CENTER : Gravity.TOP);
        }
        if (medicine != null) {
            fillMedicineSummary(patientPanels[0], medicine);
            fillMedicineContents(patientPanels[1], medicine);
            fillMedicinePackage(patientPanels[2], medicine);
            return;
        }
        if (patient == null) {
            for (LinearLayout panel : patientPanels) {
                TextView none = text("NO PATIENT", 30, Color.GREEN);
                none.setGravity(Gravity.CENTER_HORIZONTAL);
                panel.addView(none);
                TextView hint = text("SCAN A PATIENT'S QR CODE", 22, Color.GREEN);
                hint.setGravity(Gravity.CENTER_HORIZONTAL);
                hint.setPadding(0, 8, 0, 0);
                panel.addView(hint);
                TextView other = text("OR A MEDICINE'S BARCODE", 22, Color.GREEN);
                other.setGravity(Gravity.CENTER_HORIZONTAL);
                panel.addView(other);
            }
            return;
        }
        fillSummary(patientPanels[0], patient);
        fillConditionsAndMedications(patientPanels[1], patient);
        fillResults(patientPanels[2], patient);
    }

    /** Who the patient is, what to watch for, and the two numbers looked at first. */
    private void fillSummary(LinearLayout panel, PatientReport report) {
        LinearLayout who = row(44);
        who.addView(iconBox(IconView.PERSON, 40), new LinearLayout.LayoutParams(40, 40));
        TextView id = text(report.patientId, 30, Color.GREEN);
        id.setPadding(12, 0, 12, 0);
        who.addView(id, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        who.addView(text(joined("  ·  ", report.sex, report.age.isEmpty() ? "" : report.age + "Y"),
                26, Color.GREEN));
        panel.addView(who);
        // The report has no name in it: this one is made up, and stays with the patient ID.
        panel.addView(text(caps(PatientNames.nameFor(this, report.patientId, report.sex)),
                26, Color.GREEN));
        if (!report.birthDate.isEmpty()) {
            panel.addView(text("DOB " + report.birthDate, 22, Color.GREEN));
        }
        panel.addView(divider());

        // The alerts would be red on a colour display.
        if (report.alerts.isEmpty()) {
            panel.addView(text("NO ALERTS", 24, Color.GREEN));
        }
        int alertRows = Math.min(report.alerts.size(), ALERT_ROWS);
        for (int i = 0; i < alertRows; i++) {
            boolean cut = i == alertRows - 1 && report.alerts.size() > alertRows;
            panel.addView(alertBlock(
                    cut ? "+" + (report.alerts.size() - i) + " MORE ALERTS" : report.alerts.get(i), i > 0));
        }
        panel.addView(divider());

        LinearLayout vitals = row(36);
        vitals.addView(text("BP " + orDashes(report.bloodPressure), 26, Color.GREEN),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        vitals.addView(text("A1C " + orDashes(report.a1c), 26, Color.GREEN),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        panel.addView(vitals);
        panel.addView(divider());

        // What the next card holds.
        panel.addView(text(counted(report.conditions.size(), "CONDITION") + "  ·  "
                + counted(report.medications.size(), "ACTIVE MED"), 22, Color.GREEN));
    }

    /** The problem list with ICD-10 codes and year of onset, then what the patient is taking. */
    private void fillConditionsAndMedications(LinearLayout panel, PatientReport report) {
        List<String[]> conditions = new ArrayList<>();
        for (PatientReport.Condition condition : report.conditions) {
            conditions.add(new String[] {caps(condition.name),
                    joined(" · ", condition.code, condition.onset)});
        }
        List<String[]> medications = new ArrayList<>();
        boolean dated = false;
        for (PatientReport.Medication medication : report.medications) {
            medications.add(new String[] {caps(medication.name), medication.ordered});
            dated |= !medication.ordered.isEmpty();
        }
        // Each list gets half the lines, and a short list leaves its spare lines to the other.
        // An empty list still takes one line, to say so.
        int conditionLines = Math.max(1, conditions.size());
        int medicationLines = Math.max(1, medications.size());
        int conditionRows = Math.min(conditionLines, Math.max(LIST_ROWS / 2, LIST_ROWS - medicationLines));
        int medicationRows = Math.min(medicationLines, LIST_ROWS - conditionRows);

        panel.addView(heading(IconView.CROSS, "CONDITIONS", report.patientId));
        addEntries(panel, conditions, conditionRows);
        panel.addView(divider());
        panel.addView(heading(IconView.PILL, "MEDICATIONS", dated ? "ORDERED" : ""));
        addEntries(panel, medications, medicationRows);
    }

    /** The latest labs and measurements, each with its unit. */
    private void fillResults(LinearLayout panel, PatientReport report) {
        List<String[]> results = new ArrayList<>();
        for (PatientReport.Observation result : report.observations) {
            // Results are usually taken together, so the heading carries the date, and only a
            // result from another day shows its own.
            boolean otherDay = !result.date.equals(report.observationsDate);
            results.add(new String[] {result.label,
                    joined(result.unit.equals("%") ? "" : " ", result.value, result.unit)
                            + (otherDay ? "  " + result.date : "")});
        }
        panel.addView(heading(IconView.DROP, joined(" ", "RESULTS", report.observationsDate),
                report.patientId));
        addEntries(panel, results, RESULT_ROWS);
    }

    // ---------------------------------------------------------------- medicine cards

    /** What the medicine is, anything to be warned of first, and what it is for. */
    private void fillMedicineSummary(LinearLayout panel, Medicine medicine) {
        LinearLayout what = row(44);
        what.addView(iconBox(IconView.PILL, 40), new LinearLayout.LayoutParams(40, 40));
        TextView name = text(caps(medicine.name()), 30, Color.GREEN);
        name.setPadding(12, 0, 12, 0);
        what.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        what.addView(text(medicine.type, 26, Color.GREEN));
        panel.addView(what);
        // Under a brand name, what the drug itself is called.
        if (!medicine.genericName.isEmpty() && !medicine.genericName.equalsIgnoreCase(medicine.name())) {
            panel.addView(text(caps(medicine.genericName), 26, Color.GREEN));
        }
        panel.addView(text(joined("  ·  ", medicine.strength(), caps(medicine.form), caps(medicine.route)),
                22, Color.GREEN));
        panel.addView(divider());

        // These would be red on a colour display, like a patient's alerts.
        if (medicine.boxedWarning) {
            panel.addView(alertBlock(joined(": ", "BOXED", medicine.boxedWarningTitle.isEmpty()
                    ? "WARNING" : medicine.boxedWarningTitle), false));
        }
        if (!medicine.schedule.isEmpty()) {
            panel.addView(alertBlock("CONTROLLED  ·  " + caps(medicine.schedule), medicine.boxedWarning));
        }
        if (!medicine.boxedWarning && medicine.schedule.isEmpty()) {
            // Only a label that was read can be said to carry no boxed warning.
            panel.addView(text(medicine.labelRead ? "NO BOXED WARNING" : "LABEL NOT READ", 24, Color.GREEN));
        }
        panel.addView(divider());

        // What it is for, when the label or the directory says.
        if (!medicine.use().isEmpty()) {
            panel.addView(text(caps(medicine.use()), 26, Color.GREEN));
            panel.addView(divider());
        }

        // The code, and what the next card holds.
        panel.addView(text(joined("  ·  ", "NDC " + orDashes(medicine.ndc()),
                counted(medicine.ingredients.size(), "INGREDIENT")), 22, Color.GREEN));
    }

    /** What is in it, with strengths, then the classes of drug it belongs to. */
    private void fillMedicineContents(LinearLayout panel, Medicine medicine) {
        List<String[]> ingredients = new ArrayList<>();
        for (Medicine.Ingredient ingredient : medicine.ingredients) {
            ingredients.add(new String[] {caps(ingredient.name), ingredient.strength});
        }
        List<String[]> classes = new ArrayList<>();
        for (Medicine.DrugClass drugClass : medicine.classes) {
            classes.add(new String[] {caps(drugClass.name), drugClass.kind});
        }
        // The lines are shared out as on the patient's conditions and medications card.
        int ingredientLines = Math.max(1, ingredients.size());
        int classLines = Math.max(1, classes.size());
        int ingredientRows = Math.min(ingredientLines, Math.max(LIST_ROWS / 2, LIST_ROWS - classLines));
        int classRows = Math.min(classLines, LIST_ROWS - ingredientRows);

        // No name in the heading: a long one would leave no room for the title.
        panel.addView(heading(IconView.PILL, "ACTIVE INGREDIENTS", ingredients.isEmpty() ? "" : "STRENGTH"));
        addEntries(panel, ingredients, ingredientRows);
        panel.addView(divider());
        panel.addView(heading(IconView.CROSS, "DRUG CLASS", ""));
        addEntries(panel, classes, classRows);
    }

    /** The package that was scanned, and where the rest comes from. */
    private void fillMedicinePackage(LinearLayout panel, Medicine medicine) {
        panel.addView(heading(IconView.DROP, "PACKAGE", medicine.ndc()));
        if (medicine.packageLines.isEmpty()) {
            // The barcode matched the product, but not one of its packages.
            panel.addView(entry(counted(medicine.packageSizes, "PACKAGE SIZE") + " LISTED", ""));
        }
        for (String line : medicine.packageLines) {
            panel.addView(entry(line, ""));
        }
        panel.addView(divider());
        panel.addView(heading(IconView.CROSS, "FDA LISTING", medicine.fdaDate));
        panel.addView(entry(caps(orDashes(medicine.labeler)), ""));
        panel.addView(entry("PRODUCT", orDashes(medicine.productNdc)));
        panel.addView(entry("MARKETED AS", caps(orDashes(medicine.marketing))));
        panel.addView(entry("BARCODE", orDashes(medicine.barcode)));
    }

    /**
     * Adds a list, one line per entry: {name, detail}. Only `rows` lines are used; when the
     * list is longer, the last of them says how many entries are left out.
     */
    private void addEntries(LinearLayout panel, List<String[]> entries, int rows) {
        if (entries.isEmpty()) {
            panel.addView(entry("NONE RECORDED", ""));
        }
        for (int i = 0; i < entries.size() && i < rows; i++) {
            if (i == rows - 1 && entries.size() > rows) {
                panel.addView(entry("+" + (entries.size() - i) + " MORE", ""));
            } else {
                panel.addView(entry(entries.get(i)[0], entries.get(i)[1]));
            }
        }
    }

    /** A list's heading: its icon and title on the left, a note on the right. */
    private LinearLayout heading(int icon, String title, String note) {
        LinearLayout heading = row(40);
        heading.addView(iconBox(icon, 36), new LinearLayout.LayoutParams(36, 36));
        TextView name = text(title, 24, Color.GREEN);
        name.setPadding(10, 0, 10, 0);
        heading.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        heading.addView(text(note, 22, Color.GREEN));
        return heading;
    }

    /** One line of a list: a name on the left, cut short if it is too long, a detail on the right. */
    private LinearLayout entry(String name, String detail) {
        LinearLayout entry = row(26);
        TextView left = text(name, 22, Color.GREEN);
        left.setPadding(0, 0, 12, 0);
        entry.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        entry.addView(text(detail, 22, Color.GREEN));
        return entry;
    }

    /** An alert as a solid block, the text and icon cut out of it. */
    private LinearLayout alertBlock(String alert, boolean gapAbove) {
        GradientDrawable solid = new GradientDrawable();
        solid.setColor(Color.GREEN);
        solid.setCornerRadius(8);
        LinearLayout block = row(40);
        if (gapAbove) {
            ((LinearLayout.LayoutParams) block.getLayoutParams()).topMargin = 4;
        }
        block.setBackground(solid);
        block.setPadding(12, 0, 12, 0);
        block.addView(new IconView(this, IconView.ALERT, Color.BLACK), new LinearLayout.LayoutParams(30, 28));
        TextView label = text(alert, 26, Color.BLACK);
        label.setPadding(12, 0, 0, 0);
        block.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return block;
    }

    private LinearLayout row(int height) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        return row;
    }

    /** Bold sans-serif text for the patient cards, on one line, ending in "..." if it doesn't fit. */
    private TextView text(String text, int sizeSp, int colour) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(colour);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private View divider() {
        View line = new View(this);
        line.setBackgroundColor(Color.GREEN);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2);
        params.topMargin = 6;
        params.bottomMargin = 6;
        line.setLayoutParams(params);
        return line;
    }

    /** An icon inside a rounded outline, `size` px square. */
    private FrameLayout iconBox(int icon, int size) {
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.BLACK);
        outline.setStroke(3, Color.GREEN);
        outline.setCornerRadius(8);
        FrameLayout box = new FrameLayout(this);
        box.setBackground(outline);
        box.addView(new IconView(this, icon, Color.GREEN),
                new FrameLayout.LayoutParams(size - 14, size - 14, Gravity.CENTER));
        return box;
    }

    private static String caps(String text) {
        return text.toUpperCase(Locale.US);
    }

    /** The parts that aren't empty, with the separator between them. */
    private static String joined(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                joined.append(joined.length() == 0 ? "" : separator).append(part);
            }
        }
        return joined.toString();
    }

    /** "1 CONDITION", "3 CONDITIONS". */
    private static String counted(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "S");
    }

    private static String orDashes(String value) {
        return value.isEmpty() ? "--" : value;
    }

    /**
     * Acts on a head flick, or on the arrow key that stands in for one.
     * Returns whether anything changed.
     */
    private boolean navigate(int direction) {
        if (testState != TEST_OFF) {
            return false;
        }
        if (cardHidden) {
            if (direction != UP) {
                return false;
            }
            cardHidden = false;
            reportReady = false;   // a flick up opens a waiting report straight away
        } else if (direction == DOWN) {
            cardHidden = true;
        } else if (direction == LEFT && cardIndex > 0) {
            cardIndex--;
            leftArrow.flash();
        } else if (direction == RIGHT && cardIndex < CARD_COUNT - 1) {
            cardIndex++;
            rightArrow.flash();
        } else {
            // Nothing that way: the first and last cards are the ends of the deck.
            return false;
        }
        Log.i(TAG, cardHidden ? "card hidden" : "card " + (cardIndex + 1) + " of " + CARD_COUNT);
        showCard();
        return true;
    }

    /** Recomputes the rates once a second, and refreshes the flick test's screen four times a second. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - windowStart;
            if (elapsed >= 1000) {
                gyroHz = gyroEvents * 1000f / elapsed;
                gyroEvents = 0;
                windowStart = now;
                networkStatus = networkAddress();
                Log.i(TAG, String.format(Locale.US, "gyro %s %.0f Hz | net %s",
                        gyroscope == null ? "absent" : "present", gyroHz, networkStatus));
            }
            if (testState != TEST_OFF) {
                renderTest();
            }
            ui.postDelayed(this, 250);
        }
    };

    // ---------------------------------------------------------------- sensors

    private void probeSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : all) {
            Log.i(TAG, "sensor: " + sensor.getName() + " (" + sensor.getStringType() + ", "
                    + sensor.getVendor() + ")");
        }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        // Kept short: the line has to fit inside the card at PROBE_TEXT_SP.
        sensorSummary = "accel " + yesNo(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
                + ", rot " + yesNo(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
                + ", " + all.size() + " total";
        Log.i(TAG, "sensors: gyro " + yesNo(gyroscope) + ", " + sensorSummary);
    }

    private static String yesNo(Sensor sensor) {
        return sensor != null ? "yes" : "no";
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE) {
            return;
        }
        gyroEvents++;
        lastGyroEventAt = SystemClock.elapsedRealtime();
        System.arraycopy(event.values, 0, gyro, 0, 3);
        if (testState == TEST_NORMAL || testState == TEST_GO) {
            testSamples.add(new float[] {
                    SystemClock.elapsedRealtime() - testPhaseStart, gyro[0], gyro[1], gyro[2]});
        }
        // The sensor's own clock, in ms: bursts are timed to within a sample.
        long now = event.timestamp / 1000000L;
        // Worn, +x is a turn to the left and -y is looking up (see the constants above).
        trackBurst(yawBurst, gyro[0], gyro[1], YAW_THRESHOLD, PITCH_THRESHOLD, LEFT, RIGHT, now);
        trackBurst(pitchBurst, -gyro[1], gyro[0], PITCH_THRESHOLD, YAW_THRESHOLD, UP, DOWN, now);
    }

    /** One axis's burst: a stretch where the speed stays above BURST_ONSET in one direction. */
    private static final class Burst {
        int sign;       // +1 or -1 while a burst is running, 0 between bursts
        long start;
        float peak;     // the fastest speed so far
        float cross;    // the fastest speed on the other axis meanwhile
    }

    /**
     * Follows one axis and reports a flick when a burst on it ends, if the burst was fast
     * enough, short enough, and mostly along this axis.
     *
     * @param speed          this axis's angular speed; positive is the "positive" direction
     * @param cross          the other axis's angular speed
     * @param threshold      the speed a flick on this axis must reach
     * @param crossThreshold the same for the other axis
     */
    private void trackBurst(Burst burst, float speed, float cross, float threshold,
            float crossThreshold, int positive, int negative, long now) {
        if (burst.sign != 0 && speed * burst.sign >= BURST_ONSET) {
            burst.peak = Math.max(burst.peak, Math.abs(speed));
            burst.cross = Math.max(burst.cross, Math.abs(cross));
            return;
        }
        if (burst.sign != 0) {
            // The burst has just ended. Up flicks have their own, looser limits.
            int direction = burst.sign > 0 ? positive : negative;
            float needed = direction == UP ? UP_THRESHOLD : threshold;
            long longest = direction == UP ? UP_BURST_MAX_MS : BURST_MAX_MS;
            if (burst.peak >= needed) {
                long length = now - burst.start;
                boolean vertical = direction == UP || direction == DOWN;
                // How fast the head was turning sideways during this burst, counting a
                // sideways burst that is still running.
                float sideways = !vertical ? 0f
                        : Math.max(burst.cross, yawBurst.sign != 0 ? yawBurst.peak : 0f);
                if (length < BURST_MIN_MS) {
                    // One or two samples: a jolt or a bump, not a head movement.
                    Log.i(TAG, String.format(Locale.US, "no flick: jolt %s %.1f rad/s %d ms",
                            DIRECTION_NAMES[direction], burst.peak, length));
                } else if (length > longest) {
                    // Fast enough but too long: an ordinary head movement.
                    flickStatus = String.format(Locale.US, "slow %s %.1f rad/s %d ms",
                            DIRECTION_NAMES[direction], burst.peak, length);
                    Log.i(TAG, "no flick: " + flickStatus);
                } else if (vertical && sideways >= SIDEWAYS_LIMIT) {
                    // Sideways flicks dip and rise as the head arcs, so sideways wins: an up
                    // or down flick only counts while the head is hardly turning.
                    Log.i(TAG, String.format(Locale.US, "no flick: %s %.1f rad/s during a sideways %.1f",
                            DIRECTION_NAMES[direction], burst.peak, sideways));
                } else if (!vertical && burst.cross > VERTICAL_RATIO * burst.peak) {
                    // Far more up or down than sideways: not a sideways flick.
                    Log.i(TAG, String.format(Locale.US, "no flick: diagonal %s %.1f rad/s, across %.1f",
                            DIRECTION_NAMES[direction], burst.peak, burst.cross));
                } else {
                    flick(direction, burst.peak, length, now);
                }
            }
            burst.sign = 0;
        }
        if (Math.abs(speed) >= BURST_ONSET) {
            burst.sign = speed > 0 ? 1 : -1;
            burst.start = now;
            burst.peak = Math.abs(speed);
            burst.cross = Math.abs(cross);
        }
    }

    /** A burst that qualifies as a flick: weeds out swing-backs, then acts on it. */
    private void flick(int direction, float peak, long length, long now) {
        boolean opposite = lastFlick >= 0 && direction == (lastFlick ^ 1)
                && now - lastFlickAt < FLICK_RETURN_MS;
        // A much faster flick straight after one the other way: the first was a wind-up.
        boolean windUp = opposite && peak >= WINDUP_RATIO * lastFlickPeak;
        if (opposite && !windUp) {
            Log.i(TAG, String.format(Locale.US, "no flick: swing back %s %.1f rad/s",
                    DIRECTION_NAMES[direction], peak));
            return;
        }
        if (!windUp && now - lastFlickAt < FLICK_COOLDOWN_MS) {
            return;
        }
        flickStatus = String.format(Locale.US, "%s %.1f rad/s %d ms",
                DIRECTION_NAMES[direction], peak, length);
        Log.i(TAG, "flick " + flickStatus + (windUp ? " (replaces a wind-up)" : ""));
        if (testState != TEST_OFF) {
            // The flick test only notes what was detected; the cards stay put.
            if (windUp && !testDetected.isEmpty()) {
                testDetected.remove(testDetected.size() - 1);
            }
            testDetected.add(direction);
        } else {
            if (windUp && lastFlickMoved) {
                navigate(direction);   // undo what the wind-up did
            }
            lastFlickMoved = navigate(direction);
        }
        lastFlick = direction;
        lastFlickAt = now;
        lastFlickPeak = peak;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------------------------------------------------------------- flick test

    /** One recorded window: the normal-movement baseline, or a single prompted flick. */
    private static final class Trial {
        final int number;            // 0 for the baseline, then 1, 2, 3...
        final String prompt;         // "NORMAL", "LEFT", "RIGHT", "UP" or "DOWN"
        final List<float[]> samples;
        final List<Integer> detected;   // the flicks the detector reported during the window
        final float[] peak = new float[3];   // the fastest value on each axis, with its sign
        int axis;                    // the axis that moved fastest
        int firstSwing = 1;          // +1 or -1: which way that axis went first
        float peakMagnitude;         // the fastest speed over all three axes together

        Trial(int number, String prompt, List<float[]> samples, List<Integer> detected) {
            this.number = number;
            this.prompt = prompt;
            this.samples = samples;
            this.detected = detected;
            for (float[] sample : samples) {
                for (int i = 0; i < 3; i++) {
                    if (Math.abs(sample[i + 1]) > Math.abs(peak[i])) {
                        peak[i] = sample[i + 1];
                    }
                }
                peakMagnitude = Math.max(peakMagnitude, (float) Math.sqrt(sample[1] * sample[1]
                        + sample[2] * sample[2] + sample[3] * sample[3]));
            }
            for (int i = 1; i < 3; i++) {
                if (Math.abs(peak[i]) > Math.abs(peak[axis])) {
                    axis = i;
                }
            }
            // A flick swings out and back, so the direction is the sign of the first big swing,
            // which is not always the sign of the fastest sample.
            for (float[] sample : samples) {
                if (Math.abs(sample[axis + 1]) >= 0.5f * Math.abs(peak[axis])) {
                    firstSwing = sample[axis + 1] < 0 ? -1 : 1;
                    break;
                }
            }
        }

        /** The fastest speed on the fastest axis, in rad/s. This is what a threshold is compared with. */
        float speed() {
            return Math.abs(peak[axis]);
        }

        /** For example "+y" or "-x". */
        String direction() {
            return (firstSwing < 0 ? "-" : "+") + "xyz".charAt(axis);
        }

        /** Whether the detector reported the prompted flick. */
        boolean caught() {
            for (int direction : detected) {
                if (DIRECTION_NAMES[direction].equals(prompt)) {
                    return true;
                }
            }
            return false;
        }

        /** What the detector reported, for example "LEFT", "LEFT+RIGHT" or "" for nothing. */
        String detectedNames() {
            StringBuilder names = new StringBuilder();
            for (int direction : detected) {
                names.append(names.length() == 0 ? "" : "+").append(DIRECTION_NAMES[direction]);
            }
            return names.toString();
        }
    }

    /** A tap starts the test from the probe screen, and leaves the results screen. */
    private void onTap() {
        if (!FLICK_TEST_ENABLED) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastTapAt < TAP_DEBOUNCE_MS) {
            return;
        }
        lastTapAt = now;
        if (testState == TEST_OFF) {
            startFlickTest();
        } else if (testState == TEST_RESULTS) {
            endFlickTest();
        }
    }

    private void startFlickTest() {
        if (gyroscope == null) {
            lastInput = "no gyroscope, no flick test";
            return;
        }
        // The gyroscope has been seen to go quiet while the glasses are off the head.
        if (SystemClock.elapsedRealtime() - lastGyroEventAt > 1000) {
            lastInput = "gyro silent: wear the glasses";
            Log.w(TAG, "flick test not started: no gyroscope events in the last second");
            return;
        }
        testRunId = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        testTrials.clear();
        normalTrial = null;
        lastTrialSummary = "";
        testTrial = 0;
        testResults.setText("");
        // The test takes over the card, whichever one was showing.
        cardIndex = SUMMARY_CARD;
        cardHidden = false;
        reportReady = false;
        Log.i(TAG, "flick test " + testRunId + " started");
        beginPhase(TEST_NORMAL, NORMAL_MS);
        showCard();
    }

    /** Back to the probe screen. Also cancels a test that is still running. */
    private void endFlickTest() {
        ui.removeCallbacks(nextPhase);
        testState = TEST_OFF;
        showCard();
    }

    private void beginPhase(int state, long length) {
        testState = state;
        testPhaseStart = SystemClock.elapsedRealtime();
        testPhaseLength = length;
        testSamples.clear();
        testDetected.clear();
        ui.removeCallbacks(nextPhase);
        ui.postDelayed(nextPhase, length);
        renderTest();
    }

    private final Runnable nextPhase = new Runnable() {
        @Override
        public void run() {
            switch (testState) {
                case TEST_NORMAL:
                    normalTrial = new Trial(0, "NORMAL", new ArrayList<>(testSamples),
                            new ArrayList<>(testDetected));
                    Log.i(TAG, String.format(Locale.US,
                            "flick test: normal movement peak %.2f rad/s (%s), false flicks: %s",
                            normalTrial.speed(), normalTrial.direction(),
                            normalTrial.detected.isEmpty() ? "none" : normalTrial.detectedNames()));
                    beginPhase(TEST_READY, READY_MS);
                    break;
                case TEST_READY:
                    beginPhase(TEST_GO, GO_MS);
                    break;
                case TEST_GO:
                    Trial trial = new Trial(testTrial + 1,
                            FLICK_DIRECTIONS[testTrial % FLICK_DIRECTIONS.length],
                            new ArrayList<>(testSamples), new ArrayList<>(testDetected));
                    testTrials.add(trial);
                    String outcome = trial.caught() ? "detected"
                            : trial.detected.isEmpty() ? "missed" : "read as " + trial.detectedNames();
                    lastTrialSummary = String.format(Locale.US, "%s %.1f rad/s %s",
                            trial.prompt, trial.speed(), outcome);
                    Log.i(TAG, String.format(Locale.US,
                            "flick test: %d %s -> %s %.2f rad/s (x%+.2f y%+.2f z%+.2f, magnitude %.2f), %s",
                            trial.number, trial.prompt, trial.direction(), trial.speed(),
                            trial.peak[0], trial.peak[1], trial.peak[2], trial.peakMagnitude, outcome));
                    testTrial++;
                    if (testTrial < FLICK_TOTAL) {
                        beginPhase(TEST_READY, READY_MS);
                    } else {
                        showFlickResults();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private void renderTest() {
        long left = Math.max(0, testPhaseLength - (SystemClock.elapsedRealtime() - testPhaseStart));
        String direction = FLICK_DIRECTIONS[Math.min(testTrial, FLICK_TOTAL - 1) % FLICK_DIRECTIONS.length];
        String count = (testTrial + 1) + " of " + FLICK_TOTAL;
        switch (testState) {
            case TEST_NORMAL:
                testBig.setText("MOVE NORMALLY");
                testSub.setText(String.format(Locale.US, "look around, nod, talk   %d s",
                        (left + 999) / 1000));
                break;
            case TEST_READY:
                testBig.setText("NEXT: " + direction);
                testSub.setText("hold still   " + count
                        + (lastTrialSummary.isEmpty() ? "" : "\nlast: " + lastTrialSummary));
                break;
            case TEST_GO:
                testBig.setText("FLICK " + direction);
                testSub.setText("now   " + count);
                break;
            case TEST_RESULTS:
                testBig.setText("FLICK TEST");
                testSub.setText("peak rad/s   * = missed   tap: back");
                break;
            default:
                break;
        }
    }

    private void showFlickResults() {
        testState = TEST_RESULTS;
        int sampleCount = normalTrial == null ? 0 : normalTrial.samples.size();
        for (Trial trial : testTrials) {
            sampleCount += trial.samples.size();
        }
        if (sampleCount == 0) {
            // Nothing to report or save: zeros here would look like a real measurement.
            testResults.setText("NO GYRO DATA\nthe gyroscope sent nothing.\n"
                    + "check GYRO shows about 99 Hz\nand run the test again.");
            Log.w(TAG, "flick test " + testRunId + " recorded no gyroscope samples");
            renderTest();
            return;
        }
        float normal = normalTrial == null ? 0 : normalTrial.speed();
        int falseFlicks = normalTrial == null ? 0 : normalTrial.detected.size();
        int caught = 0;
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.US, "NORMAL    %4.1f   false flicks %d\n", normal, falseFlicks));
        for (String direction : FLICK_DIRECTIONS) {
            String axis = null;
            boolean mixed = false;
            StringBuilder speeds = new StringBuilder();
            for (Trial trial : testTrials) {
                if (!trial.prompt.equals(direction)) {
                    continue;
                }
                if (axis == null) {
                    axis = trial.direction();
                } else if (!axis.equals(trial.direction())) {
                    mixed = true;
                }
                // A star marks a flick the detector did not report.
                speeds.append(String.format(Locale.US, " %4.1f%s", trial.speed(),
                        trial.caught() ? " " : "*"));
                if (trial.caught()) {
                    caught++;
                }
            }
            text.append(String.format(Locale.US, "%-5s %-3s%s\n", direction, mixed ? "mix" : axis, speeds));
        }
        String verdict = "DETECTED " + caught + " of " + testTrials.size();
        text.append(verdict);
        testResults.setText(text);
        Log.i(TAG, String.format(Locale.US, "flick test %s done: %s, normal peak %.2f rad/s, false flicks %d",
                testRunId, verdict, normal, falseFlicks));
        saveFlickTest(testRunId, normalTrial, new ArrayList<>(testTrials));
        renderTest();
    }

    /**
     * Appends one row per trial to flick_trials_v2.csv and every gyro sample to
     * flick_samples.csv. (flick_trials.csv, from the earlier probe, has no "detected" column.)
     */
    private void saveFlickTest(final String runId, final Trial normal, final List<Trial> trials) {
        final File folder = getExternalFilesDir(null);
        if (folder == null) {
            Log.w(TAG, "flick test: no external files folder, results not saved");
            return;
        }
        final List<Trial> all = new ArrayList<>();
        if (normal != null) {
            all.add(normal);
        }
        all.addAll(trials);
        new Thread(() -> {
            File summary = new File(folder, "flick_trials_v2.csv");
            File raw = new File(folder, "flick_samples.csv");
            boolean newSummary = !summary.exists();
            boolean newRaw = !raw.exists();
            try (PrintWriter out = new PrintWriter(new FileWriter(summary, true));
                 PrintWriter samples = new PrintWriter(new FileWriter(raw, true))) {
                if (newSummary) {
                    out.println("run,trial,prompt,axis,first_swing,peak_rad_s,"
                            + "peak_x,peak_y,peak_z,peak_magnitude,samples,detected");
                }
                if (newRaw) {
                    samples.println("run,trial,prompt,ms,x,y,z");
                }
                for (Trial trial : all) {
                    out.println(String.format(Locale.US, "%s,%d,%s,%c,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%s",
                            runId, trial.number, trial.prompt, "xyz".charAt(trial.axis),
                            trial.firstSwing, trial.speed(), trial.peak[0], trial.peak[1],
                            trial.peak[2], trial.peakMagnitude, trial.samples.size(),
                            trial.detectedNames()));
                    for (float[] sample : trial.samples) {
                        samples.println(String.format(Locale.US, "%s,%d,%s,%.0f,%.4f,%.4f,%.4f",
                                runId, trial.number, trial.prompt,
                                sample[0], sample[1], sample[2], sample[3]));
                    }
                }
                Log.i(TAG, "flick test saved to " + folder);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "flick test: could not save results", e);
            }
        }, "flick-save").start();
    }

    // ---------------------------------------------------------------- input

    /** Keys that should keep their usual job instead of starting the flick test. */
    private static boolean isSystemKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_POWER || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            input("Key " + KeyEvent.keyCodeToString(event.getKeyCode()));
            // Arrow keys stand in for flicks, so the cards can be tested over adb.
            switch (event.getKeyCode()) {
                // The touchpad's double tap: exit, which also forgets what was scanned.
                case KeyEvent.KEYCODE_F12: finish(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: navigate(LEFT); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: navigate(RIGHT); return true;
                case KeyEvent.KEYCODE_DPAD_UP: navigate(UP); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: navigate(DOWN); return true;
                default: break;
            }
            // Any other key, the touchpad's tap included, starts or leaves the flick test.
            if (event.getRepeatCount() == 0 && !isSystemKey(event.getKeyCode())) {
                onTap();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        input("Motion " + MotionEvent.actionToString(event.getAction()));
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            input("Touch " + Math.round(event.getX()) + ", " + Math.round(event.getY()));
            onTap();
        }
        return super.dispatchTouchEvent(event);
    }

    private void input(String text) {
        Log.i(TAG, "input: " + text);
        lastInput = text;
    }

    // ---------------------------------------------------------------- helpers

    private boolean hasPackage(String name) {
        try {
            getPackageManager().getPackageInfo(name, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** The first IPv4 address of a network that is up, for example "wlan0 192.168.1.20". */
    private static String networkAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address) {
                        return network.getName() + " " + address.getHostAddress();
                    }
                }
            }
        } catch (SocketException | RuntimeException e) {
            return "unknown";
        }
        return "none";
    }

    // ---------------------------------------------------------------- arrows

    /** A simple line icon, drawn with strokes thick enough to stay sharp through the lens. */
    private static final class IconView extends View {
        static final int PERSON = 0, ALERT = 1, PILL = 2, CROSS = 3, DROP = 4;
        private final int icon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        IconView(Context context, int icon, int colour) {
            super(context);
            this.icon = icon;
            paint.setColor(colour);
            paint.setStrokeWidth(3);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (icon) {
                case PERSON:
                    canvas.drawCircle(w / 2, h * 0.30f, h * 0.20f, paint);
                    path.moveTo(w * 0.10f, h * 0.94f);
                    path.cubicTo(w * 0.10f, h * 0.56f, w * 0.90f, h * 0.56f, w * 0.90f, h * 0.94f);
                    canvas.drawPath(path, paint);
                    break;
                case ALERT:
                    path.moveTo(w / 2, 2);
                    path.lineTo(w - 2, h - 2);
                    path.lineTo(2, h - 2);
                    path.close();
                    canvas.drawPath(path, paint);
                    canvas.drawLine(w / 2, h * 0.40f, w / 2, h * 0.62f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(w / 2, h * 0.80f, 2f, paint);
                    break;
                case CROSS: {
                    // The outline of a medical cross.
                    float near = 2, a = 0.34f, b = 0.66f;
                    path.moveTo(w * a, near);
                    path.lineTo(w * b, near);
                    path.lineTo(w * b, h * a);
                    path.lineTo(w - near, h * a);
                    path.lineTo(w - near, h * b);
                    path.lineTo(w * b, h * b);
                    path.lineTo(w * b, h - near);
                    path.lineTo(w * a, h - near);
                    path.lineTo(w * a, h * b);
                    path.lineTo(near, h * b);
                    path.lineTo(near, h * a);
                    path.lineTo(w * a, h * a);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                }
                case DROP:
                    // A drop, for results from a sample: a point at the top, round below.
                    path.moveTo(w / 2, 2);
                    path.cubicTo(w * 0.50f, h * 0.25f, w * 0.88f, h * 0.50f, w * 0.88f, h * 0.68f);
                    path.cubicTo(w * 0.88f, h * 1.02f, w * 0.12f, h * 1.02f, w * 0.12f, h * 0.68f);
                    path.cubicTo(w * 0.12f, h * 0.50f, w * 0.50f, h * 0.25f, w / 2, 2);
                    path.close();
                    canvas.drawPath(path, paint);
                    break;
                default:
                    // A capsule on the diagonal, with a line across its middle.
                    canvas.save();
                    canvas.rotate(-45, w / 2, h / 2);
                    rect.set(w * 0.04f, h * 0.30f, w * 0.96f, h * 0.70f);
                    canvas.drawRoundRect(rect, h * 0.20f, h * 0.20f, paint);
                    canvas.drawLine(w / 2, h * 0.30f, w / 2, h * 0.70f, paint);
                    canvas.restore();
                    break;
            }
        }
    }

    /** A triangle outline pointing one way. It fills in briefly when its flick is recognised. */
    private static final class ArrowView extends View {
        private final int direction;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private boolean filled;
        private final Runnable unfill = () -> {
            filled = false;
            invalidate();
        };

        ArrowView(Context context, int direction) {
            super(context);
            this.direction = direction;
            paint.setColor(Color.GREEN);
            paint.setStrokeWidth(3);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void flash() {
            filled = true;
            invalidate();
            removeCallbacks(unfill);
            postDelayed(unfill, 250);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            // Inset by the stroke so the outline is not clipped at the view's edge.
            float near = 3, right = getWidth() - 3, bottom = getHeight() - 3;
            float midX = getWidth() / 2f, midY = getHeight() / 2f;
            path.reset();
            switch (direction) {
                case LEFT:
                    path.moveTo(near, midY);
                    path.lineTo(right, near);
                    path.lineTo(right, bottom);
                    break;
                case RIGHT:
                    path.moveTo(right, midY);
                    path.lineTo(near, near);
                    path.lineTo(near, bottom);
                    break;
                case UP:
                    path.moveTo(midX, near);
                    path.lineTo(right, bottom);
                    path.lineTo(near, bottom);
                    break;
                default:
                    path.moveTo(midX, bottom);
                    path.lineTo(near, near);
                    path.lineTo(right, near);
                    break;
            }
            path.close();
            paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
            canvas.drawPath(path, paint);
        }
    }
}
