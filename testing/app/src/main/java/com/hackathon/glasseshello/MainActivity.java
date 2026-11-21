package com.hackathon.glasseshello;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hardware probe for the M6 glasses (640x480, Android 9), using plain Android APIs only.
 *
 * It answers the questions the kit's documents leave open:
 *   - can our own app open the camera, and how many frames per second does it deliver?
 *   - can our own app record from the microphone?
 *   - is there a gyroscope (a cheaper way to detect head flicks than camera CV)?
 *   - is the display full colour or green only?  (look at the RED / GREEN / BLUE / WHITE row)
 *   - is Google Play Services installed (needed for ML Kit)?
 *   - which key events do the temple touchpad and the ring send?
 *
 * The probe is the main screen: the first of a small deck of cards, which is also the test
 * bed for head-flick navigation. A card is a rounded outline in the middle of the display
 * with arrows around it:
 *   - flick left or right: move to the next card that way (the others just show their number);
 *   - flick down: hide the card, leaving an up arrow at the bottom of the display;
 *   - flick up: bring the card back.
 * The arrow keys do the same, for testing without wearing the glasses:
 *   adb shell input keyevent DPAD_LEFT      (or DPAD_RIGHT, DPAD_DOWN, DPAD_UP)
 *
 * Black pixels are see-through on the waveguide, so the screen is black with bright text.
 * Everything shown is also logged once a second:  adb logcat -s GlassesProbe
 *
 * Camera and microphone need runtime permissions. The easiest way to grant them is at install:
 *   adb install -g -r app-debug.apk
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "GlassesProbe";

    // Flick directions. Opposites differ only in the lowest bit.
    private static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;
    private static final String[] DIRECTION_NAMES = {"LEFT", "RIGHT", "UP", "DOWN"};

    /** Angular speed, in rad/s, above which a head turn (left/right) counts as a flick. */
    private static final float YAW_THRESHOLD = 2.0f;
    /** The same for a nod (up/down), which is naturally a smaller movement. */
    private static final float PITCH_THRESHOLD = 1.5f;
    /** Below this speed on both axes the head counts as still. */
    private static final float FLICK_REST = 0.5f;
    /** The head must be still this long before the next flick can register. */
    private static final long FLICK_REARM_MS = 120;
    private static final long FLICK_COOLDOWN_MS = 500;
    /** A flick the opposite way this soon after another is the head swinging back. */
    private static final long FLICK_RETURN_MS = 1000;
    /**
     * The sensor axis that runs ear to ear, pointing to the wearer's right: rotating about it
     * in the positive direction is looking up. Android's convention for a landscape display is
     * +x. If nodding down shows "UP" on the FLICK line, negate this.
     */
    private static final float[] PITCH_AXIS = {1f, 0f, 0f};

    /** Card 1 is the probe; the others show their number. */
    private static final int CARD_COUNT = 4;
    private static final int PROBE_CARD = 0;
    private static final int CARD_WIDTH = 456, CARD_HEIGHT = 288;
    /** Text size inside the probe card: the largest at which its longest lines fit. */
    private static final int PROBE_TEXT_SP = 16;
    /** Arrows are triangles: this long across the base and this deep to the point. */
    private static final int ARROW_BASE = 48, ARROW_DEPTH = 30;

    private static final String GRANT_HINT = "no permission (adb install -g)";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView displayLine, cameraLine, fpsLine, micLine, gyroLine, flickLine, sensorLine,
            systemLine, inputLine;

    // Cards
    private FrameLayout card;
    private LinearLayout probePanel;
    private TextView numberLabel;
    private ArrowView leftArrow, rightArrow, downArrow, upArrow;
    private int cardIndex = PROBE_CARD;
    private boolean cardHidden;

    // Camera
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private volatile boolean cameraWanted;
    private volatile String cameraStatus = "starting";
    private final AtomicInteger frameCount = new AtomicInteger();
    private long totalFrames;
    private float cameraFps;

    // Microphone
    private volatile Thread micThread;   // the thread that should be recording; null when stopped
    private volatile String micStatus = "starting";

    // Sensors
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private String sensorSummary = "";
    private final float[] gyro = new float[3];
    private int gyroEvents;
    private float gyroHz;
    /** Smoothed accelerometer reading, and the unit vector it gives for "up" in sensor axes. */
    private final float[] gravity = new float[3];
    private final float[] up = {0f, 1f, 0f};
    private boolean haveGravity;
    private boolean flickArmed;
    private long restSince;
    private int lastFlick = -1;
    private long lastFlickAt;
    private String flickStatus = "turn your head quickly";

    private String displaySummary = "";
    private String gmsStatus = "";
    private String networkStatus = "";
    private String lastInput = "tap the touchpad";
    private long windowStart;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildScreen();

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        displaySummary = metrics.widthPixels + "x" + metrics.heightPixels + " @ " + metrics.densityDpi + " dpi";
        gmsStatus = hasPackage("com.google.android.gms") ? "present" : "absent";
        Log.i(TAG, "display " + displaySummary + ", Google Play Services " + gmsStatus);

        probeSensors();

        // Ask once. The probes start in onResume, which runs again when the dialog closes.
        if (savedInstanceState == null && (!granted(Manifest.permission.CAMERA)
                || !granted(Manifest.permission.RECORD_AUDIO))) {
            requestPermissions(
                    new String[] {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startCamera();
        startMic();
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        flickArmed = false;
        restSince = 0;
        windowStart = SystemClock.elapsedRealtime();
        frameCount.set(0);
        gyroEvents = 0;
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        sensorManager.unregisterListener(this);
        stopMic();
        stopCamera();
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
        card.setPadding(16, 10, 16, 10);
        root.addView(card, at(cardLeft, cardTop, CARD_WIDTH, CARD_HEIGHT));

        probePanel = new LinearLayout(this);
        probePanel.setOrientation(LinearLayout.VERTICAL);

        // On a green-only display RED and BLUE come out dim or missing.
        LinearLayout colours = new LinearLayout(this);
        colours.setOrientation(LinearLayout.HORIZONTAL);
        colours.addView(label("M6 PROBE  ", Color.WHITE));
        colours.addView(label("RED ", Color.RED));
        colours.addView(label("GREEN ", Color.GREEN));
        colours.addView(label("BLUE ", Color.BLUE));
        colours.addView(label("WHITE", Color.WHITE));
        probePanel.addView(colours);

        displayLine = line(probePanel);
        cameraLine = line(probePanel);
        fpsLine = line(probePanel);
        micLine = line(probePanel);
        gyroLine = line(probePanel);
        flickLine = line(probePanel);
        sensorLine = line(probePanel);
        systemLine = line(probePanel);
        inputLine = line(probePanel);
        card.addView(probePanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // The other cards are placeholders: just their number.
        numberLabel = new TextView(this);
        numberLabel.setTextColor(Color.GREEN);
        numberLabel.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        numberLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 120);
        numberLabel.setGravity(Gravity.CENTER);
        card.addView(numberLabel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

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

    private TextView line(LinearLayout root) {
        TextView view = new TextView(this);
        view.setTextColor(Color.GREEN);
        // Bold, like the header row: regular-weight strokes look soft through the lens.
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, PROBE_TEXT_SP);
        view.setSingleLine(true);
        view.setPadding(0, 3, 0, 0);
        root.addView(view);
        return view;
    }

    // ---------------------------------------------------------------- cards

    /** Shows the current card with its arrows, or only the up arrow while the card is hidden. */
    private void showCard() {
        int shown = cardHidden ? View.INVISIBLE : View.VISIBLE;
        card.setVisibility(shown);
        leftArrow.setVisibility(shown);
        rightArrow.setVisibility(shown);
        downArrow.setVisibility(shown);
        upArrow.setVisibility(cardHidden ? View.VISIBLE : View.INVISIBLE);

        boolean probe = cardIndex == PROBE_CARD;
        probePanel.setVisibility(probe ? View.VISIBLE : View.INVISIBLE);
        numberLabel.setVisibility(probe ? View.INVISIBLE : View.VISIBLE);
        numberLabel.setText(String.valueOf(cardIndex + 1));
    }

    /** Acts on a head flick, or on the arrow key that stands in for one. */
    private void navigate(int direction) {
        if (cardHidden) {
            if (direction != UP) {
                return;
            }
            cardHidden = false;
        } else if (direction == DOWN) {
            cardHidden = true;
        } else if (direction == LEFT) {
            cardIndex = (cardIndex + CARD_COUNT - 1) % CARD_COUNT;
            leftArrow.flash();
        } else if (direction == RIGHT) {
            cardIndex = (cardIndex + 1) % CARD_COUNT;
            rightArrow.flash();
        } else {
            return;
        }
        Log.i(TAG, cardHidden ? "card hidden" : "card " + (cardIndex + 1) + " of " + CARD_COUNT);
        showCard();
    }

    /** Refreshes the screen four times a second and recomputes the rates once a second. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            long elapsed = now - windowStart;
            if (elapsed >= 1000) {
                int frames = frameCount.getAndSet(0);
                cameraFps = frames * 1000f / elapsed;
                totalFrames += frames;
                gyroHz = gyroEvents * 1000f / elapsed;
                gyroEvents = 0;
                windowStart = now;
                networkStatus = networkAddress();
                Log.i(TAG, String.format(Locale.US,
                        "camera [%s] %.1f fps | mic [%s] | gyro %s %.0f Hz | net %s",
                        cameraStatus, cameraFps, micStatus,
                        gyroscope == null ? "absent" : "present", gyroHz, networkStatus));
            }
            render();
            ui.postDelayed(this, 250);
        }
    };

    private void render() {
        displayLine.setText("DISPLAY " + displaySummary);
        cameraLine.setText("CAMERA  " + cameraStatus);
        fpsLine.setText(String.format(Locale.US, "FPS     %.1f  (%d frames)", cameraFps, totalFrames));
        micLine.setText("MIC     " + micStatus);
        if (gyroscope == null) {
            gyroLine.setText("GYRO    not present");
        } else {
            gyroLine.setText(String.format(Locale.US, "GYRO    %.0f Hz x%+.1f y%+.1f z%+.1f",
                    gyroHz, gyro[0], gyro[1], gyro[2]));
        }
        flickLine.setText("FLICK   " + flickStatus);
        sensorLine.setText("SENSORS " + sensorSummary);
        systemLine.setText("GMS " + gmsStatus + "  NET " + networkStatus);
        inputLine.setText("INPUT   " + lastInput);
    }

    // ---------------------------------------------------------------- camera

    private void startCamera() {
        if (!granted(Manifest.permission.CAMERA)) {
            cameraStatus = GRANT_HINT;
            return;
        }
        cameraWanted = true;
        cameraStatus = "opening";
        cameraThread = new HandlerThread("camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                cameraStatus = "none found";
                return;
            }
            for (String id : ids) {
                logCamera(manager, id);
            }

            final String id = ids[0];
            CameraCharacteristics traits = manager.getCameraCharacteristics(id);
            StreamConfigurationMap map = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888));
            final String about = "id " + id + " " + facing(traits) + " " + size.getWidth() + "x"
                    + size.getHeight() + (ids.length > 1 ? " (" + ids.length + " cams)" : "");

            // Frames are only counted and dropped: this measures what the camera can deliver.
            final ImageReader reader = ImageReader.newInstance(
                    size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(source -> {
                try {
                    Image image = source.acquireNextImage();
                    if (image != null) {
                        image.close();
                        frameCount.incrementAndGet();
                    }
                } catch (IllegalStateException e) {
                    // The reader was closed while a frame was on its way.
                }
            }, cameraHandler);
            imageReader = reader;
            final Surface surface = reader.getSurface();

            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice device) {
                    if (!cameraWanted) {
                        device.close();
                        return;
                    }
                    cameraDevice = device;
                    startSession(device, surface, about);
                }

                @Override
                public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraStatus = "disconnected (in use elsewhere?)";
                    Log.w(TAG, "camera disconnected");
                }

                @Override
                public void onError(CameraDevice device, int error) {
                    device.close();
                    cameraStatus = "open error " + error;
                    Log.e(TAG, "camera open error " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            cameraStatus = "failed: " + e.getClass().getSimpleName();
            Log.e(TAG, "camera probe failed", e);
        }
    }

    private void startSession(final CameraDevice device, final Surface surface, final String about) {
        try {
            device.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (!cameraWanted) {
                                return;
                            }
                            captureSession = session;
                            try {
                                CaptureRequest.Builder request =
                                        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(surface);
                                session.setRepeatingRequest(request.build(), null, cameraHandler);
                                cameraStatus = about;
                                Log.i(TAG, "camera streaming: " + about);
                            } catch (CameraAccessException | RuntimeException e) {
                                cameraStatus = "stream failed: " + e.getClass().getSimpleName();
                                Log.e(TAG, "camera stream failed", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            cameraStatus = "session refused";
                            Log.e(TAG, "camera session configuration failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            cameraStatus = "session failed: " + e.getClass().getSimpleName();
            Log.e(TAG, "camera session failed", e);
        }
    }

    private void stopCamera() {
        cameraWanted = false;
        try {
            if (captureSession != null) {
                captureSession.close();
            }
            if (cameraDevice != null) {
                cameraDevice.close();
            }
            if (imageReader != null) {
                imageReader.close();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "closing the camera", e);
        }
        captureSession = null;
        cameraDevice = null;
        imageReader = null;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    /** 640x480 if the camera offers it, otherwise the smallest size at least 320 wide. */
    private static Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return new Size(640, 480);
        }
        Size best = null;
        for (Size size : sizes) {
            if (size.getWidth() == 640 && size.getHeight() == 480) {
                return size;
            }
            if (size.getWidth() >= 320 && (best == null
                    || size.getWidth() * size.getHeight() < best.getWidth() * best.getHeight())) {
                best = size;
            }
        }
        return best != null ? best : sizes[0];
    }

    private static String facing(CameraCharacteristics traits) {
        Integer facing = traits.get(CameraCharacteristics.LENS_FACING);
        if (facing == null) {
            return "?";
        }
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT: return "front";
            case CameraCharacteristics.LENS_FACING_BACK: return "back";
            default: return "external";
        }
    }

    private static void logCamera(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics traits = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = traits.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Log.i(TAG, "camera " + id + ": facing " + facing(traits)
                + ", hardware level " + traits.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                + " (0 limited, 1 full, 2 legacy, 3 level 3, 4 external)"
                + ", fps ranges " + Arrays.toString(
                        traits.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))
                + ", YUV sizes " + (map == null ? "none"
                        : Arrays.toString(map.getOutputSizes(ImageFormat.YUV_420_888))));
    }

    // ---------------------------------------------------------------- microphone

    private void startMic() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            micStatus = GRANT_HINT;
            return;
        }
        micStatus = "opening";
        Thread thread = new Thread(this::runMic, "mic");
        micThread = thread;
        thread.start();
    }

    private void stopMic() {
        micThread = null;
    }

    /** Opens the microphone at the first rate it accepts and reports the input level. */
    private void runMic() {
        AudioRecord recorder = null;
        int rate = 0;
        // 16 kHz is what speech recognition wants; the others are fallbacks.
        for (int candidate : new int[] {16000, 44100, 48000}) {
            int minimum = AudioRecord.getMinBufferSize(candidate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                continue;
            }
            try {
                AudioRecord opened = new AudioRecord(MediaRecorder.AudioSource.MIC, candidate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minimum, candidate * 2 / 5));
                if (opened.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder = opened;
                    rate = candidate;
                    break;
                }
                opened.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "mic refused " + candidate + " Hz", e);
            }
        }
        if (recorder == null) {
            micStatus = "could not open";
            Log.e(TAG, "mic: could not open at any rate");
            return;
        }

        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                micStatus = "would not start (in use elsewhere?)";
                Log.e(TAG, "mic: opened at " + rate + " Hz but would not start");
                return;
            }
            Log.i(TAG, "mic recording at " + rate + " Hz, mono, 16-bit");
            short[] samples = new short[rate / 10];
            while (micThread == Thread.currentThread()) {
                int read = recorder.read(samples, 0, samples.length);
                if (read <= 0) {
                    micStatus = "read error " + read;
                    Log.e(TAG, "mic read error " + read);
                    return;
                }
                double sum = 0;
                for (int i = 0; i < read; i++) {
                    sum += (double) samples[i] * samples[i];
                }
                double rms = Math.sqrt(sum / read);
                if (rms < 1) {
                    micStatus = rate + " Hz  silent (all zeros)";
                } else {
                    long level = Math.round(20 * Math.log10(rms / 32768.0));
                    micStatus = rate + " Hz  " + level + " dB " + bar(level);
                }
            }
        } catch (RuntimeException e) {
            micStatus = "failed: " + e.getClass().getSimpleName();
            Log.e(TAG, "mic probe failed", e);
        } finally {
            try {
                recorder.stop();
            } catch (IllegalStateException e) {
                // It never started.
            }
            recorder.release();
        }
    }

    /** A level meter: -60 dB is empty, 0 dB is ten blocks. */
    private static String bar(long level) {
        int blocks = (int) Math.max(0, Math.min(10, (level + 60) / 6));
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < blocks; i++) {
            bar.append('#');
        }
        return bar.toString();
    }

    // ---------------------------------------------------------------- sensors

    private void probeSensors() {
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        List<Sensor> all = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor sensor : all) {
            Log.i(TAG, "sensor: " + sensor.getName() + " (" + sensor.getStringType() + ", "
                    + sensor.getVendor() + ")");
        }
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorSummary = "accel " + yesNo(accelerometer)
                + ", rotation " + yesNo(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
                + ", " + all.size() + " total";
        Log.i(TAG, "sensors: gyro " + yesNo(gyroscope) + ", " + sensorSummary);
    }

    private static String yesNo(Sensor sensor) {
        return sensor != null ? "yes" : "no";
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_ACCELEROMETER) {
            trackUp(event.values);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            gyroEvents++;
            System.arraycopy(event.values, 0, gyro, 0, 3);
            detectFlick();
        }
    }

    /** Smooths the accelerometer to find which way is up, in the sensor's own axes. */
    private void trackUp(float[] accel) {
        float blend = haveGravity ? 0.03f : 1f;
        for (int i = 0; i < 3; i++) {
            gravity[i] += blend * (accel[i] - gravity[i]);
        }
        haveGravity = true;
        float length = (float) Math.sqrt(dot(gravity, gravity));
        if (length > 1f) {
            for (int i = 0; i < 3; i++) {
                up[i] = gravity[i] / length;
            }
        }
    }

    /**
     * Turns the gyroscope reading into LEFT / RIGHT / UP / DOWN flicks.
     *
     * Turning the head left or right is rotation about the vertical, so that part is the
     * reading's component along "up" and does not depend on how the sensor is mounted.
     * Looking up or down is rotation about the ear-to-ear axis, which is PITCH_AXIS with its
     * vertical part removed.
     */
    private void detectFlick() {
        float yaw = dot(gyro, up);
        float lean = dot(PITCH_AXIS, up);
        float[] across = new float[3];
        for (int i = 0; i < 3; i++) {
            across[i] = PITCH_AXIS[i] - lean * up[i];
        }
        float span = (float) Math.sqrt(dot(across, across));
        float pitch = span > 0.2f ? dot(gyro, across) / span : 0f;

        long now = SystemClock.elapsedRealtime();
        if (Math.abs(yaw) < FLICK_REST && Math.abs(pitch) < FLICK_REST) {
            if (restSince == 0) {
                restSince = now;
            }
            if (now - restSince >= FLICK_REARM_MS) {
                flickArmed = true;
            }
            return;
        }
        restSince = 0;

        float yawScore = Math.abs(yaw) / YAW_THRESHOLD;
        float pitchScore = Math.abs(pitch) / PITCH_THRESHOLD;
        float score = Math.max(yawScore, pitchScore);
        if (!flickArmed || score < 1f || now - lastFlickAt < FLICK_COOLDOWN_MS) {
            return;
        }
        // A diagonal movement is neither one nor the other; wait for a clearer one.
        if (Math.min(yawScore, pitchScore) * 1.5f > score) {
            return;
        }
        flickArmed = false;
        int direction = yawScore >= pitchScore ? (yaw > 0 ? LEFT : RIGHT) : (pitch > 0 ? UP : DOWN);
        if (direction == (lastFlick ^ 1) && now - lastFlickAt < FLICK_RETURN_MS) {
            return;   // the head swinging back
        }
        lastFlick = direction;
        lastFlickAt = now;
        flickStatus = String.format(Locale.US, "%s %.1f x%+.1f y%+.1f z%+.1f",
                DIRECTION_NAMES[direction], Math.max(Math.abs(yaw), Math.abs(pitch)),
                gyro[0], gyro[1], gyro[2]);
        Log.i(TAG, String.format(Locale.US, "flick %s | yaw %+.1f pitch %+.1f | up %+.2f %+.2f %+.2f",
                flickStatus, yaw, pitch, up[0], up[1], up[2]));
        navigate(direction);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            input("Key " + KeyEvent.keyCodeToString(event.getKeyCode()));
            // Arrow keys stand in for flicks, so the cards can be tested over adb.
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: navigate(LEFT); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: navigate(RIGHT); return true;
                case KeyEvent.KEYCODE_DPAD_UP: navigate(UP); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: navigate(DOWN); return true;
                default: break;
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
        }
        return super.dispatchTouchEvent(event);
    }

    private void input(String text) {
        Log.i(TAG, "input: " + text);
        lastInput = text;
    }

    // ---------------------------------------------------------------- helpers

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

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
