package dev.xenoah.spectrum.core;

import java.util.Arrays;

/** Reused snapshots: copy only while holding the producer's monitor. */
public final class SpectrumFrame {
    public static final int BANDS = 48, WAVE_POINTS = 384;
    public final float[] bands = new float[BANDS];
    public final float[] peaks = new float[BANDS];
    public final float[] wave = new float[WAVE_POINTS];
    public float rmsDb = -120, peakDb = -120, dominantHz, maxFrequency = 20000;
    public float binHz, windowMs, waveMs = 12;
    public int sampleRate;
    public long sequence;
    public boolean clipping;

    public SpectrumFrame() { Arrays.fill(bands, -120); Arrays.fill(peaks, -120); }

    public void copyFrom(SpectrumFrame f) {
        System.arraycopy(f.bands, 0, bands, 0, BANDS);
        System.arraycopy(f.peaks, 0, peaks, 0, BANDS);
        System.arraycopy(f.wave, 0, wave, 0, WAVE_POINTS);
        rmsDb = f.rmsDb; peakDb = f.peakDb; dominantHz = f.dominantHz;
        maxFrequency = f.maxFrequency; binHz = f.binHz; windowMs = f.windowMs;
        waveMs = f.waveMs; sampleRate = f.sampleRate; sequence = f.sequence; clipping = f.clipping;
    }
}
