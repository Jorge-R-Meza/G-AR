package com.hackathon.glasseshello;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Typeface;
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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.WindowManager;
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
 * Black pixels are see-through on the waveguide, so the screen is black with bright text.
 * Everything shown is also logged once a second:  adb logcat -s GlassesProbe
 *
 * Camera and microphone need runtime permissions. The easiest way to grant them is at install:
 *   adb install -g -r app-debug.apk
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String TAG = "GlassesProbe";

    /** Angular speed, in rad/s, above which a head movement counts as a flick. */
    private static final float FLICK_THRESHOLD = 2.0f;
    private static final long FLICK_COOLDOWN_MS = 600;

    private static final String GRANT_HINT = "no permission (adb install -g)";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView displayLine, cameraLine, fpsLine, micLine, gyroLine, flickLine, sensorLine,
            systemLine, inputLine;

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
    private String sensorSummary = "";
    private final float[] gyro = new float[3];
    private int gyroEvents;
    private float gyroHz;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // The glasses' launcher draws its status bar over the top 36 px of every app.
        root.setPadding(24, 44, 24, 16);

        // On a green-only display RED and BLUE come out dim or missing.
        LinearLayout colours = new LinearLayout(this);
        colours.setOrientation(LinearLayout.HORIZONTAL);
        colours.addView(label("M6 PROBE  ", Color.WHITE));
        colours.addView(label("RED ", Color.RED));
        colours.addView(label("GREEN ", Color.GREEN));
        colours.addView(label("BLUE ", Color.BLUE));
        colours.addView(label("WHITE", Color.WHITE));
        root.addView(colours);

        displayLine = line(root);
        cameraLine = line(root);
        fpsLine = line(root);
        micLine = line(root);
        gyroLine = line(root);
        flickLine = line(root);
        sensorLine = line(root);
        systemLine = line(root);
        inputLine = line(root);

        setContentView(root);
    }

    private TextView label(String text, int colour) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(colour);
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        return view;
    }

    private TextView line(LinearLayout root) {
        TextView view = new TextView(this);
        view.setTextColor(Color.GREEN);
        // Bold, like the header row: regular-weight strokes look soft through the lens.
        view.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setSingleLine(true);
        view.setPadding(0, 8, 0, 0);
        root.addView(view);
        return view;
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
        sensorSummary = "accel " + yesNo(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
                + ", rotation " + yesNo(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
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
        System.arraycopy(event.values, 0, gyro, 0, 3);

        // The fastest axis and its direction. Which axis is "left/right" on the glasses is
        // something to read off this line while wearing them.
        int axis = 0;
        for (int i = 1; i < 3; i++) {
            if (Math.abs(gyro[i]) > Math.abs(gyro[axis])) {
                axis = i;
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (Math.abs(gyro[axis]) > FLICK_THRESHOLD && now - lastFlickAt > FLICK_COOLDOWN_MS) {
            lastFlickAt = now;
            flickStatus = String.format(Locale.US, "%s%c  %.1f rad/s",
                    gyro[axis] > 0 ? "+" : "-", "xyz".charAt(axis), Math.abs(gyro[axis]));
            Log.i(TAG, "flick " + flickStatus);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            input("Key " + KeyEvent.keyCodeToString(event.getKeyCode()));
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
}
