package dev.xenoah.spectrum;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
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
    private static final int STOPPED = 0, BLOCKED = 1, EMPTY = 2;
    private final AudioManager manager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SpectrumFrame latest = new SpectrumFrame();
    private Session active;
    private Future<?> pending;
    private long published, consumed;
    private String status = "STARTING", detail = "Opening microphone...", source = "", diagnostic = "", inputSummary = "";
    private long streamEpoch, consumedEpoch;

    private static final class Input {
        final int source, rate;
        Input(int source, int rate) { this.source = source; this.rate = rate; }
    }

    private static final class Session {
        volatile boolean cancelled, resetPeaks;
        final int input;
        Session(int input) { this.input = input; }
    }

    AudioEngine(Context context) { manager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE); }

    synchronized void start(int input) {
        stop();
        final Session session = new Session(input);
        active = session; status = "STARTING"; detail = "Opening microphone..."; source = ""; diagnostic = ""; inputSummary = ""; consumed = published;
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
        ui.inputSummary = inputSummary;
        if (consumedEpoch != streamEpoch) {
            ui.haveFrame = false; ui.clearHistory(); consumedEpoch = streamEpoch;
        }
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

    private synchronized void summary(Session s, String info) {
        if (active == s && !s.cancelled) inputSummary = info;
    }

    private synchronized void clearStream(Session s) {
        if (active == s && !s.cancelled) { streamEpoch++; consumed = published; }
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
            List<Input> inputs = inputs(s.input);
            int blocked = 0, empty = 0, errors = 0, checked = 0;
            // A finite scan of non-private inputs. Initialization alone is not success:
            // an initialized recorder may still be silenced by the device's audio policy.
            for (Input input : inputs) {
                if (s.cancelled) return;
                while (!s.cancelled && isSystemMuted()) {
                    state(s, "MIC MUTED", "Microphone is muted in system settings.", "");
                    Thread.sleep(120);
                }
                if (s.cancelled) return;
                checked++;
                AudioRecord record = null;
                try {
                    clearStream(s);
                    state(s, "SCANNING", "Checking shared input " + checked + " / " + inputs.size() + "...", "");
                    summary(s, "API " + Build.VERSION.SDK_INT + " / " + modeName() + " / OS ROUTE");
                    diagnostics(s, sourceName(input.source) + " / " + input.rate + " Hz / NONPRIVATE");
                    record = open(s, input);
                    if (record == null) {
                        errors++;
                        Log.w(TAG, sourceName(input.source) + " " + input.rate + " Hz: could not open");
                        continue;
                    }
                    if (s.cancelled) return;
                    // Leave shared AGC/NS/AEC preprocessing to Android and the assistant.
                    String label = sourceName(input.source) + " / NONPRIVATE";
                    diagnostics(s, sourceName(input.source) + " / " + record.getSampleRate() + " Hz / NONPRIVATE");
                    int result = read(s, record, label, checked, inputs.size());
                    if (result == STOPPED || s.cancelled) return;
                    if (result == BLOCKED) blocked++; else empty++;
                    Log.w(TAG, sourceName(input.source) + " " + record.getSampleRate() + " Hz: "
                        + (result == BLOCKED ? "policy silenced" : "zero-only input"));
                } catch (SecurityException denied) {
                    state(s, "PERMISSION", "Microphone permission is required.", ""); return;
                } catch (RuntimeException unavailable) {
                    Log.w(TAG, "Input " + input.source + " unavailable", unavailable);
                    errors++;
                } finally {
                    if (record != null) {
                        try { record.stop(); } catch (RuntimeException ignored) { }
                        try { record.release(); } catch (RuntimeException ignored) { }
                    }
                }
                if (!s.cancelled) Thread.sleep(60);
            }
            clearStream(s);
            summary(s, "TESTED " + checked + " / BLOCKED " + blocked + " / ZERO " + empty + " / ERR " + errors);
            state(s, blocked > 0 ? "MIC BLOCKED" : empty > 0 ? "NO SIGNAL" : "MIC ERROR",
                blocked > 0 ? "System policy silenced the tested inputs."
                : empty > 0 ? "Only digital silence was received." : "No supported microphone input opened.", "");
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            Log.e(TAG, "Capture failed", failure);
            state(s, "MIC ERROR", "Microphone stopped. Tap to retry.", "");
        }
    }

    private List<Input> inputs(int mode) {
        List<Input> result = new ArrayList<>();
        if (mode >= 1 && mode <= 3) {
            int source = mode == 1 ? MediaRecorder.AudioSource.UNPROCESSED
                : mode == 2 ? MediaRecorder.AudioSource.VOICE_RECOGNITION : MediaRecorder.AudioSource.MIC;
            result.add(new Input(source, 48000)); result.add(new Input(source, 16000));
            return result;
        }
        boolean raw = false;
        try { raw = manager != null && "true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)); }
        catch (RuntimeException ignored) { }
        result.add(new Input(MediaRecorder.AudioSource.MIC, 48000));
        result.add(new Input(MediaRecorder.AudioSource.VOICE_RECOGNITION, 48000));
        if (raw) result.add(new Input(MediaRecorder.AudioSource.UNPROCESSED, 48000));
        if (Build.VERSION.SDK_INT >= 29) result.add(new Input(MediaRecorder.AudioSource.VOICE_PERFORMANCE, 48000));
        // Probe the speech rate even if 48 kHz initializes successfully but receives no audio.
        result.add(new Input(MediaRecorder.AudioSource.MIC, 16000));
        result.add(new Input(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000));
        if (Build.VERSION.SDK_INT >= 30) {
            // These routes default to private. Use them ONLY where false can be set and verified
            // before startRecording(), never as private/exclusive fallbacks.
            result.add(new Input(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000));
            result.add(new Input(MediaRecorder.AudioSource.CAMCORDER, 48000));
        }
        result.add(new Input(MediaRecorder.AudioSource.DEFAULT, 48000));
        return result;
    }

    private AudioRecord open(Session s, Input input) {
        for (int rate : input.rate == 16000 ? new int[]{16000} : new int[]{48000, 44100, 32000, 16000}) {
            if (s.cancelled) return null;
            AudioRecord record = null;
            try {
                int minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) continue;
                AudioFormat format = new AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
                AudioRecord.Builder builder = new AudioRecord.Builder().setAudioSource(input.source)
                    .setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum * 4, SpectrumAnalyzer.HOP * 8));
                if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);
                record = builder.build();
                if (record.getState() != AudioRecord.STATE_INITIALIZED) { record.release(); continue; }
                if (Build.VERSION.SDK_INT >= 30 && record.isPrivacySensitive()) {
                    record.release(); record = null;
                    throw new IllegalStateException("Non-private input request was not honored");
                }
                // Let Android choose a route shared with its current microphone users.
                // Pinning the first TYPE_BUILTIN_MIC can select the wrong vendor input path.
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

    private int read(final Session s, final AudioRecord record, final String label, int index, int total) throws InterruptedException {
        SpectrumAnalyzer analyzer = new SpectrumAnalyzer(record.getSampleRate());
        final short[] buffer = new short[SpectrumAnalyzer.HOP];
        long started = SystemClock.elapsedRealtime(), lastData = started, lastNonzero = started;
        long blockedSince = -1;
        boolean signal = false, discardWindow = false;
        final boolean[] measured = {false};
        while (!s.cancelled) {
            int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            long now = SystemClock.elapsedRealtime();
            if (count < 0) throw new IllegalStateException("AudioRecord read: " + count);
            boolean silenced = isSilenced(record), muted = isSystemMuted();
            if (silenced || muted) {
                // Discard any queued PCM, even if it is nonzero. Do not show it as a current measurement.
                if (!discardWindow) clearStream(s);
                discardWindow = true; signal = false; lastData = now;
                if (muted) {
                    blockedSince = -1;
                    state(s, "MIC MUTED", "Microphone is muted in system settings.", label);
                } else {
                    if (blockedSince < 0) blockedSince = now;
                    state(s, measured[0] ? "WAITING" : "SCANNING", measured[0] ? busyDetail()
                        : "Input " + index + " / " + total + " silenced; trying shared routes.", label);
                    // Brief assistant interactions can recover in-place; persistent denial
                    // advances to the next non-private candidate, never waits forever.
                    if (now - blockedSince >= (measured[0] ? 4000 : 800)) return BLOCKED;
                }
                Thread.sleep(20); continue;
            }
            if (discardWindow) {
                analyzer = new SpectrumAnalyzer(record.getSampleRate());
                clearStream(s);
                discardWindow = false; blockedSince = -1; lastNonzero = started = lastData = now;
                state(s, "STARTING", "Microphone recovered. Rebuilding spectrum...", label);
            }
            if (count == 0) {
                if (now - lastData > 2000) throw new IllegalStateException("AudioRecord stalled");
                Thread.sleep(6); continue;
            }
            lastData = now;
            for (int i = 0; i < count; i++) if (buffer[i] != 0) { lastNonzero = now; signal = true; break; }
            if ((!signal && now - started > 1800) || (signal && now - lastNonzero > 2500)) {
                return EMPTY;
            }
            if (s.resetPeaks) { analyzer.resetPeaks(); s.resetPeaks = false; }
            // A zero-only stream is never advertised as a successful live measurement.
            final boolean hasSignal = signal;
            analyzer.accept(buffer, count, new SpectrumAnalyzer.Listener() {
                @Override public void onFrame(SpectrumFrame frame) {
                    if (hasSignal) { publish(s, frame, label); measured[0] = true; }
                }
            });
        }
        return STOPPED;
    }

    private boolean isSystemMuted() {
        try { return manager != null && manager.isMicrophoneMute(); } catch (RuntimeException ignored) { return false; }
    }

    private String busyDetail() {
        try {
            if (manager != null && (manager.getMode() == AudioManager.MODE_IN_CALL
                || manager.getMode() == AudioManager.MODE_IN_COMMUNICATION)) return "Call / communication is using the mic.";
        } catch (RuntimeException ignored) { }
        return "System policy is silencing this input.";
    }

    private String modeName() {
        try { return manager == null ? "MODE ?" : "MODE " + manager.getMode(); }
        catch (RuntimeException ignored) { return "MODE ?"; }
    }

    private boolean isSilenced(AudioRecord record) {
        if (Build.VERSION.SDK_INT < 29) return false;
        try {
            AudioRecordingConfiguration c = record.getActiveRecordingConfiguration();
            return c != null && c.isClientSilenced();
        } catch (RuntimeException ignored) { return false; }
    }

    private String sourceName(int source) {
        switch (source) {
            case 9: return "RAW";
            case 6: return "VOICE";
            case 1: return "MIC";
            case 10: return "PERF";
            case 7: return "COMM";
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
