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
    private String status = "STARTING", detail = "Opening microphone...", source = "", diagnostic = "";

    private static final class Session {
        volatile boolean cancelled, resetPeaks;
        final int input;
        Session(int input) { this.input = input; }
    }

    AudioEngine(Context context) { manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }

    synchronized void start(int input) {
        stop();
        final Session session = new Session(input);
        active = session; status = "STARTING"; detail = "Opening microphone..."; source = ""; diagnostic = ""; consumed = published;
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
        ui.status = status; ui.detail = detail; ui.source = source; ui.diagnostic = diagnostic;
        if (published != consumed) { ui.receive(latest); consumed = published; }
    }

    private synchronized void state(Session s, String state, String message, String inputName) {
        if (active != s || s.cancelled) return;
        status = state; detail = message;
        if (inputName != null) source = inputName;
    }

    private synchronized void diagnostics(Session s, String info) {
        if (active == s && !s.cancelled) diagnostic = info;
    }

    private synchronized void publish(Session s, SpectrumFrame frame, String inputName) {
        if (active != s || s.cancelled) return;
        latest.copyFrom(frame); published++;
        source = inputName;
        status = s.input == 4 ? "DEMO" : "LIVE";
        detail = "";
    }

    private void capture(Session s) {
        try {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (RuntimeException ignored) { }
            if (s.cancelled) return;
            if (s.input == 4) { demo(s); return; }
            // Respect a system mute. Never turn it off or repeatedly open the microphone underneath it.
            while (!s.cancelled && isSystemMuted()) {
                state(s, "MIC MUTED", "Microphone is muted in system settings.", "");
                Thread.sleep(120);
            }
            if (s.cancelled) return;
            int[] sources = sources(s.input);
            String failure = "Microphone unavailable. Close other audio apps.";
            // One scan plus one retry. The retry holds the first working input for policy recovery.
            // No system process is stopped and audio mode/focus is not changed globally.
            for (int attempt = 0; attempt < sources.length * 2; attempt++) {
                int sourceCode = sources[attempt % sources.length];
                if (s.cancelled) return;
                AudioRecord record = null;
                List<AudioEffect> effects = new ArrayList<>();
                try {
                    record = open(s, sourceCode);
                    if (record == null) continue;
                    if (s.cancelled) return;
                    disableEffects(record.getAudioSessionId(), effects);
                    String label = sourceName(sourceCode) + (isPrivate(record, sourceCode) ? " / PRIVATE" : " / DEFAULT");
                    diagnostics(s, sourceName(sourceCode) + " / " + record.getSampleRate() + " Hz / "
                        + (isPrivate(record, sourceCode) ? "PRIVATE" : "DEFAULT"));
                    state(s, attempt == 0 ? "STARTING" : "RETRYING", "Listening for microphone samples...", label);
                    boolean finished = read(s, record, label, attempt < sources.length);
                    if (finished || s.cancelled) return;
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
                if (!s.cancelled) Thread.sleep(100);
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
        if (mode == 5) return new int[]{MediaRecorder.AudioSource.CAMCORDER};
        boolean raw = false;
        try { raw = manager != null && "true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)); }
        catch (RuntimeException ignored) { }
        // Standard MIC first for vendor compatibility; all API 30+ candidates request private capture.
        return raw ? new int[]{1, 6, 9, 5, 0} : new int[]{1, 6, 5, 0};
    }

    private AudioRecord open(Session s, int sourceCode) {
        for (int rate : new int[]{48000, 44100, 32000, 16000}) {
            if (s.cancelled) return null;
            AudioRecord record = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                AudioFormat format = new AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
                AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(sourceCode)
                    .setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum * 4, SpectrumAnalyzer.HOP * 8));
                if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(true);
                record = builder.build();
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

    private boolean read(final Session s, final AudioRecord record, final String label, boolean canSwitch) throws InterruptedException {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(record.getSampleRate());
        final short[] buffer = new short[SpectrumAnalyzer.HOP];
        long started = SystemClock.elapsedRealtime(), lastData = started, lastNonzero = started;
        long blockedSince = 0;
        boolean signal = false, discardWindow = false;
        while (!s.cancelled) {
            int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            long now = SystemClock.elapsedRealtime();
            if (count < 0) throw new IllegalStateException("AudioRecord read: " + count);
            boolean silenced = isSilenced(record), muted = isSystemMuted();
            if (silenced || muted) {
                // Discard any queued PCM, even if it is nonzero. Do not show it as a current measurement.
                if (blockedSince == 0) blockedSince = now;
                discardWindow = true; signal = false; lastData = now;
                state(s, muted ? "MIC MUTED" : "MIC BUSY", muted
                    ? "Microphone is muted in system settings." : busyDetail(), label);
                if (!muted && canSwitch && now - blockedSince >= 800) return false;
                Thread.sleep(20); continue;
            }
            if (discardWindow) {
                analyzer = new SpectrumAnalyzer(record.getSampleRate());
                discardWindow = false; blockedSince = 0; lastNonzero = started = lastData = now;
                state(s, "STARTING", "Microphone recovered. Rebuilding spectrum...", label);
            }
            if (count == 0) {
                if (now - lastData > 2000) throw new IllegalStateException("AudioRecord stalled");
                Thread.sleep(6); continue;
            }
            lastData = now;
            for (int i = 0; i < count; i++) if (buffer[i] != 0) { lastNonzero = now; signal = true; break; }
            if ((!signal && now - started > 1800) || (signal && now - lastNonzero > 2500)) {
                state(s, "NO SIGNAL", "Digital silence. Check mic privacy / input.", label);
                if (canSwitch) return false;
                // Stay on the last candidate so releasing another app can recover without a tap.
                analyzer = new SpectrumAnalyzer(record.getSampleRate());
                signal = false; lastNonzero = started = now;
            }
            if (s.resetPeaks) { analyzer.resetPeaks(); s.resetPeaks = false; }
            // A zero-only stream is never advertised as a successful live measurement.
            final boolean hasSignal = signal;
            analyzer.accept(buffer, count, new SpectrumAnalyzer.Listener() {
                @Override public void onFrame(SpectrumFrame frame) {
                    if (hasSignal) publish(s, frame, label);
                }
            });
        }
        return true;
    }

    private boolean isPrivate(AudioRecord record, int sourceCode) {
        if (Build.VERSION.SDK_INT >= 30) {
            try { return record.isPrivacySensitive(); } catch (RuntimeException ignored) { }
        }
        return sourceCode == MediaRecorder.AudioSource.CAMCORDER;
    }

    private boolean isSystemMuted() {
        try { return manager != null && manager.isMicrophoneMute(); } catch (RuntimeException ignored) { return false; }
    }

    private String busyDetail() {
        try {
            if (manager != null && (manager.getMode() == AudioManager.MODE_IN_CALL
                || manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)) return "Call / communication is using the mic.";
        } catch (RuntimeException ignored) { }
        return "Android input policy is muting the mic.";
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
            case 9: return "RAW";
            case 6: return "VOICE";
            case 1: return "MIC";
            case 5: return "CAM";
            default: return "DEFAULT";
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
                @Override public void onFrame(SpectrumFrame f) { publish(s, f, "DEMO / GENERATED"); }
            });
            next += 43;
            Thread.sleep(Math.max(1, next - SystemClock.elapsedRealtime()));
        }
    }
}
