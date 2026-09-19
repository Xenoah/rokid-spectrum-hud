package dev.xenoah.spectrum;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import dev.xenoah.spectrum.core.HudState;
import dev.xenoah.spectrum.core.SpectrumAnalyzer;
import dev.xenoah.spectrum.core.SpectrumFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One worker owns AudioRecord, including stop/release. No blocking audio calls on UI. */
final class AudioEngine {
    private static final String TAG = "RokidSpectrum";
    private final AudioManager manager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SpectrumFrame latest = new SpectrumFrame();
    private Session active;
    private Future<?> pending;
    private long published, consumed;
    private String status = "STARTING", detail = "Opening microphone...", source = "";

    private static final class Session {
        volatile boolean cancelled, resetPeaks;
        final int input;
        Session(int input) { this.input = input; }
    }

    AudioEngine(Context context) { manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }

    synchronized void start(int input) {
        stop();
        final Session session = new Session(input);
        active = session; status = "STARTING"; detail = "Opening microphone..."; source = ""; consumed = published;
        pending = worker.submit(new Runnable() { @Override public void run() { capture(session); } });
    }

    synchronized void stop() {
        if (active != null) active.cancelled = true;
        if (pending != null) pending.cancel(true);
        active = null; pending = null;
    }

    void close() { stop(); worker.shutdownNow(); }

    synchronized void resetPeaks() { if (active != null) active.resetPeaks = true; }

    synchronized void poll(HudState ui) {
        if (active == null || ui.frozen) return;
        ui.status = status; ui.detail = detail; ui.source = source;
        if (published != consumed) { ui.receive(latest); consumed = published; }
    }

    private synchronized void state(Session s, String state, String message, String inputName) {
        if (active != s || s.cancelled) return;
        status = state; detail = message;
        if (inputName != null) source = inputName;
    }

    private synchronized void publish(Session s, SpectrumFrame frame, String inputName, boolean silent, boolean silenced) {
        if (active != s || s.cancelled) return;
        latest.copyFrom(frame); published++;
        source = inputName;
        status = silenced ? "MIC BUSY" : silent ? "NO SIGNAL" : s.input == 4 ? "DEMO" : "LIVE";
        detail = silenced ? "Android has silenced this microphone." : silent ? "Digital silence. Check mic privacy / input." : "";
    }

    private void capture(Session s) {
        try {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (RuntimeException ignored) { }
            if (s.cancelled) return;
            if (s.input == 4) { demo(s); return; }
            int[] sources = sources(s.input);
            String failure = "Microphone unavailable. Close other audio apps.";
            for (int sourceCode : sources) {
                if (s.cancelled) return;
                AudioRecord record = null;
                List<AudioEffect> effects = new ArrayList<>();
                try {
                    record = open(s, sourceCode);
                    if (record == null) continue;
                    if (s.cancelled) return;
                    disableEffects(record.getAudioSessionId(), effects);
                    String label = sourceName(sourceCode);
                    state(s, "STARTING", "Listening for microphone samples...", label);
                    boolean hadSignal = read(s, record, label);
                    if (hadSignal || s.cancelled) return;
                    failure = "Digital silence. Check mic privacy / input.";
                } catch (SecurityException denied) {
                    state(s, "PERMISSION", "Microphone permission is required.", ""); return;
                } catch (RuntimeException unavailable) {
                    Log.w(TAG, "Input " + sourceCode + " unavailable", unavailable);
                    failure = "Microphone unavailable. Tap to retry.";
                } finally {
                    for (AudioEffect effect : effects) try { effect.release(); } catch (RuntimeException ignored) { }
                    if (record != null) {
                        try { record.stop(); } catch (RuntimeException ignored) { }
                        try { record.release(); } catch (RuntimeException ignored) { }
                    }
                }
            }
            state(s, failure.startsWith("Digital") ? "NO SIGNAL" : "MIC ERROR", failure, "");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            Log.e(TAG, "Capture failed", failure);
            state(s, "MIC ERROR", "Microphone stopped. Tap to retry.", "");
        }
    }

    private int[] sources(int mode) {
        if (mode == 1) return new int[]{MediaRecorder.AudioSource.UNPROCESSED};
        if (mode == 2) return new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION};
        if (mode == 3) return new int[]{MediaRecorder.AudioSource.MIC};
        boolean raw = false;
        try { raw = manager != null && "true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)); }
        catch (RuntimeException ignored) { }
        return raw ? new int[]{9, 6, 1, 0} : new int[]{6, 1, 0};
    }

    private AudioRecord open(Session s, int sourceCode) {
        for (int rate : new int[]{48000, 44100, 32000, 16000}) {
            if (s.cancelled) return null;
            AudioRecord record = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                record = new AudioRecord(sourceCode, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 4, SpectrumAnalyzer.HOP * 8));
                if (record.getState() != AudioRecord.STATE_INITIALIZED) { record.release(); continue; }
                preferBuiltIn(record);
                record.startRecording();
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) return record;
            } catch (SecurityException denied) {
                if (record != null) try { record.release(); } catch (RuntimeException ignored) { }
                throw denied;
            } catch (RuntimeException ignored) { }
            if (record != null) try { record.release(); } catch (RuntimeException ignored) { }
        }
        return null;
    }

    private void preferBuiltIn(AudioRecord record) {
        if (manager == null) return;
        try {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { record.setPreferredDevice(device); return; }
            }
        } catch (RuntimeException ignored) { }
    }

    private boolean read(final Session s, final AudioRecord record, final String label) throws InterruptedException {
        final SpectrumAnalyzer analyzer = new SpectrumAnalyzer(record.getSampleRate());
        final short[] buffer = new short[SpectrumAnalyzer.HOP];
        long started = SystemClock.elapsedRealtime(), lastData = started, lastNonzero = started;
        boolean signal = false;
        while (!s.cancelled) {
            int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            long now = SystemClock.elapsedRealtime();
            if (count < 0) throw new IllegalStateException("AudioRecord read: " + count);
            if (count == 0) {
                if (now - lastData > 2000) throw new IllegalStateException("AudioRecord stalled");
                Thread.sleep(6); continue;
            }
            lastData = now;
            for (int i = 0; i < count; i++) if (buffer[i] != 0) { lastNonzero = now; signal = true; break; }
            if (!signal && now - started > 1800) return false;
            final boolean silent = now - lastNonzero > 2500;
            final boolean silenced = isSilenced(record);
            if (s.resetPeaks) { analyzer.resetPeaks(); s.resetPeaks = false; }
            // A zero-only stream is never advertised as a successful live measurement.
            final boolean hasSignal = signal;
            analyzer.accept(buffer, count, new SpectrumAnalyzer.Listener() {
                @Override public void onFrame(SpectrumFrame frame) {
                    if (hasSignal) publish(s, frame, label, silent, silenced);
                    else if (silenced) state(s, "MIC BUSY", "Android has silenced this microphone.", label);
                }
            });
        }
        return signal;
    }

    private boolean isSilenced(AudioRecord record) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            AudioRecordingConfiguration c = record.getActiveRecordingConfiguration();
            return c != null && c.isClientSilenced();
        } catch (RuntimeException ignored) { return false; }
    }

    private void disableEffects(int sessionId, List<AudioEffect> effects) {
        try { if (AutomaticGainControl.isAvailable()) disable(AutomaticGainControl.create(sessionId), effects); } catch (RuntimeException ignored) { }
        try { if (NoiseSuppressor.isAvailable()) disable(NoiseSuppressor.create(sessionId), effects); } catch (RuntimeException ignored) { }
        try { if (AcousticEchoCanceler.isAvailable()) disable(AcousticEchoCanceler.create(sessionId), effects); } catch (RuntimeException ignored) { }
    }

    private void disable(AudioEffect effect, List<AudioEffect> effects) {
        if (effect != null) { effects.add(effect); effect.setEnabled(false); }
    }

    private String sourceName(int source) {
        switch (source) {
            case 9: return "MIC / RAW REQUESTED";
            case 6: return "MIC / VOICE RECOGNITION";
            case 1: return "MIC / STANDARD";
            default: return "MIC / DEFAULT";
        }
    }

    private void demo(final Session s) throws InterruptedException {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(48000);
        short[] data = new short[SpectrumAnalyzer.HOP];
        Random random = new Random(314159);
        long sample = 0, next = SystemClock.elapsedRealtime();
        state(s, "DEMO", "Generated signal. Microphone is off.", "DEMO / GENERATED");
        while (!s.cancelled) {
            for (int i = 0; i < data.length; i++, sample++) {
                double t = sample / 48000.0;
                double value = .22 * Math.sin(2 * Math.PI * 1000 * t) + .07 * Math.sin(2 * Math.PI * 250 * t)
                    + .035 * Math.sin(2 * Math.PI * 4000 * t) + .003 * (random.nextDouble() * 2 - 1);
                data[i] = (short)Math.round(32767 * value);
            }
            if (s.resetPeaks) { analyzer.resetPeaks(); s.resetPeaks = false; }
            analyzer.accept(data, data.length, new SpectrumAnalyzer.Listener() {
                @Override public void onFrame(SpectrumFrame f) { publish(s, f, "DEMO / GENERATED", false, false); }
            });
            next += 43;
            Thread.sleep(Math.max(1, next - SystemClock.elapsedRealtime()));
        }
    }
}
