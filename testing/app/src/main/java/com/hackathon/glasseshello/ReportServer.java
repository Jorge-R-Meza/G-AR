package com.hackathon.glasseshello;

import android.util.Log;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Receives patient reports from the laptop. The laptop's stream viewer reads the QR code in
 * the glasses' camera stream, fetches the link in it, and sends what the link returned here:
 * one TCP connection per report, the report's JSON as the whole of what is sent.
 * The answer is one line, "ok" or "error: ...".
 *
 * Medicines arrive the same way: the viewer reads the barcode on a package, looks it up at
 * the FDA and sends what is listed, as JSON with "medicine" at the top (see Medicine).
 *
 * KeepAliveService runs this, so it listens whenever the app's process is alive. A report is
 * only taken while the app is open, though: once the app has been exited, reports are turned
 * away until it is opened again, so a code still in view cannot refill an app just emptied.
 *
 * To send one by hand over USB:
 *   adb forward tcp:32889 tcp:32889
 *   nc localhost 32889 < report.json
 *
 * Anything on the same network can send a report: fine for a demo, not for real patients.
 */
final class ReportServer {

    /** The port after the one the camera stream goes out on. */
    static final int PORT = 32889;

    interface Listener {
        /** Called on the server's own thread. Returns whether the report was taken. */
        boolean onReport(PatientReport report);
    }

    interface MedicineListener {
        /** Called on the server's own thread. Returns whether the medicine was taken. */
        boolean onMedicine(Medicine medicine);
    }

    private static final String TAG = "GlassesProbe";
    private static final int SIZE_LIMIT = 1 << 20;
    private static final int READ_TIMEOUT_MS = 3000;

    private final Listener listener;
    private final MedicineListener medicines;
    private volatile ServerSocket server;

    ReportServer(Listener listener, MedicineListener medicines) {
        this.listener = listener;
        this.medicines = medicines;
    }

    void start() {
        Thread thread = new Thread(this::run, "report-server");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        ServerSocket open = server;
        server = null;
        if (open != null) {
            try {
                open.close();   // ends the accept() the thread is waiting in
            } catch (IOException e) {
                // Already closed.
            }
        }
    }

    private void run() {
        ServerSocket open;
        try {
            open = new ServerSocket();
            open.setReuseAddress(true);
            open.bind(new InetSocketAddress(PORT));
        } catch (IOException e) {
            Log.e(TAG, "patient reports: could not listen on port " + PORT, e);
            return;
        }
        server = open;
        Log.i(TAG, "patient reports: listening on port " + PORT);
        while (server == open) {
            try (Socket socket = open.accept()) {
                socket.getOutputStream().write((receive(socket) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (server == open) {
                    Log.w(TAG, "patient reports: connection failed", e);
                }
            }
        }
    }

    /** Reads one report, hands it to the listener, and returns the line to answer with. */
    private String receive(Socket socket) throws IOException {
        socket.setSoTimeout(READ_TIMEOUT_MS);
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        Object message = null;   // a PatientReport or a Medicine
        JSONException problem = null;
        try {
            int count;
            while (message == null && (count = in.read(buffer)) > 0) {
                received.write(buffer, 0, count);
                if (received.size() > SIZE_LIMIT) {
                    return "error: larger than " + (SIZE_LIMIT >> 20) + " MB";
                }
                // Not every sender closes its side when it is done (nc doesn't), so a report
                // that already reads as complete is taken without waiting for the end.
                if (endsWithBrace(buffer, count)) {
                    try {
                        message = parse(text(received));
                    } catch (JSONException e) {
                        problem = e;   // probably just not all here yet
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            // Whatever has arrived is all there will be.
        }
        if (message == null) {
            if (received.size() == 0) {
                return "error: nothing received";
            }
            try {
                message = parse(text(received));
            } catch (JSONException e) {
                problem = problem != null ? problem : e;
                Log.w(TAG, "patient reports: not a patient report or a medicine: " + problem.getMessage());
                return "error: " + problem.getMessage();
            }
        }
        if (message instanceof Medicine) {
            Medicine medicine = (Medicine) message;
            // A medicine is public information, so it is logged by name.
            Log.i(TAG, "medicine received: " + received.size() + " bytes, " + medicine.name()
                    + ", NDC " + medicine.ndc() + ", " + medicine.ingredients.size() + " ingredients, "
                    + medicine.classes.size() + " classes");
            if (!medicines.onMedicine(medicine)) {
                Log.i(TAG, "medicine turned away: the app is closed");
                return "error: the app is closed on the glasses";
            }
            return "ok";
        }
        PatientReport report = (PatientReport) message;
        // Only the shape is logged: the contents are personal.
        Log.i(TAG, "patient report received: " + received.size() + " bytes, "
                + report.alerts.size() + " alerts, " + report.conditions.size() + " conditions, "
                + report.medications.size() + " medications, "
                + report.observations.size() + " observations");
        if (!listener.onReport(report)) {
            Log.i(TAG, "patient report turned away: the app is closed");
            return "error: the app is closed on the glasses";
        }
        return "ok";
    }

    /** A medicine if the JSON has one at the top, otherwise a patient report. */
    private static Object parse(String json) throws JSONException {
        return Medicine.isMedicine(json) ? Medicine.parse(json) : PatientReport.parse(json);
    }

    private static boolean endsWithBrace(byte[] buffer, int count) {
        for (int i = count - 1; i >= 0; i--) {
            if (!Character.isWhitespace(buffer[i])) {
                return buffer[i] == '}';
            }
        }
        return false;
    }

    private static String text(ByteArrayOutputStream bytes) {
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
