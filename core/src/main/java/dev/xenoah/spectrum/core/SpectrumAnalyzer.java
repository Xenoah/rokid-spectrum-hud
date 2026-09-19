package dev.xenoah.spectrum.core;

import java.util.Arrays;

/** PCM16 -> overlapping Hann FFT; single audio-thread owner. */
public final class SpectrumAnalyzer {
    public static final int SIZE = 8192, HOP = 2048;
    private final int rate;
    private final Fft fft = new Fft(SIZE);
    private final float[] ring = new float[SIZE];
    private final double[] window = new double[SIZE], re = new double[SIZE], im = new double[SIZE];
    private final float[] bins = new float[SIZE / 2 + 1];
    private final int[] lo = new int[SpectrumFrame.BANDS], hi = new int[SpectrumFrame.BANDS];
    private final float[] holdSeconds = new float[SpectrumFrame.BANDS];
    private final double windowSum;
    private int write, filled, sinceFrame;
    private final SpectrumFrame frame = new SpectrumFrame();
    private boolean ready;

    public interface Listener { void onFrame(SpectrumFrame frame); }

    public SpectrumAnalyzer(int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 192000) throw new IllegalArgumentException("sample rate");
        rate = sampleRate;
        double sum = 0;
        for (int i = 0; i < SIZE; i++) { window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / SIZE); sum += window[i]; }
        windowSum = sum;
        frame.sampleRate = rate; frame.binHz = rate / (float)SIZE;
        frame.windowMs = 1000f * SIZE / rate; frame.maxFrequency = Math.min(20000, rate / 2f);
        double ratio = frame.maxFrequency / 20.0;
        for (int b = 0; b < lo.length; b++) {
            double low = 20 * Math.pow(ratio, b / (double)lo.length);
            double high = 20 * Math.pow(ratio, (b + 1.0) / lo.length);
            lo[b] = Math.max(1, (int)Math.ceil(low / frame.binHz));
            hi[b] = Math.min(SIZE / 2, (int)Math.ceil(high / frame.binHz) - 1);
            if (hi[b] < lo[b]) lo[b] = hi[b] = Math.max(1, (int)Math.round(Math.sqrt(low * high) / frame.binHz));
            if (b == lo.length - 1) hi[b] = Math.min(SIZE / 2, (int)Math.floor(frame.maxFrequency / frame.binHz));
        }
    }

    public void accept(short[] pcm, int count, Listener listener) {
        if (count < 0 || count > pcm.length) throw new IllegalArgumentException("count");
        for (int i = 0; i < count; i++) {
            ring[write] = pcm[i] / 32768f; write = (write + 1) & (SIZE - 1);
            if (filled < SIZE) filled++;
            sinceFrame++;
            if (filled == SIZE && sinceFrame >= HOP) {
                sinceFrame = 0; analyze(); listener.onFrame(frame);
            }
        }
    }

    public void resetPeaks() { Arrays.fill(frame.peaks, -120); Arrays.fill(holdSeconds, 0); }

    private void analyze() {
        double mean = 0, squares = 0, max = 0;
        for (int i = 0; i < SIZE; i++) {
            double x = ring[(write + i) & (SIZE - 1)];
            mean += x; squares += x * x; max = Math.max(max, Math.abs(x));
        }
        mean /= SIZE;
        for (int i = 0; i < SIZE; i++) {
            re[i] = (ring[(write + i) & (SIZE - 1)] - mean) * window[i]; im[i] = 0;
        }
        fft.transform(re, im);
        for (int k = 0; k < bins.length; k++) {
            double norm = (k == 0 || k == SIZE / 2) ? 1 : 2;
            bins[k] = db(Math.hypot(re[k], im[k]) * norm / windowSum);
        }
        float dt = HOP / (float)rate;
        for (int b = 0; b < lo.length; b++) {
            float value = -120;
            for (int k = lo[b]; k <= hi[b]; k++) value = Math.max(value, bins[k]);
            float tau = value > frame.bands[b] ? .035f : .22f;
            frame.bands[b] = ready ? frame.bands[b] + (float)(1 - Math.exp(-dt / tau)) * (value - frame.bands[b]) : value;
            if (value >= frame.peaks[b]) { frame.peaks[b] = value; holdSeconds[b] = 1f; }
            else if (holdSeconds[b] > 0) holdSeconds[b] -= dt;
            else frame.peaks[b] = Math.max(value, frame.peaks[b] - 18 * dt);
        }
        frame.rmsDb = db(Math.sqrt(Math.max(0, squares / SIZE - mean * mean)));
        frame.peakDb = db(max); frame.clipping = max >= 32760.0 / 32768;
        int peak = Math.max(1, (int)Math.ceil(20 / frame.binHz));
        int last = Math.min(SIZE / 2, (int)Math.floor(frame.maxFrequency / frame.binHz));
        // Locate using raw magnitude: one-sided amplitude normalization differs at Nyquist.
        for (int k = peak + 1; k <= last; k++) if (power(k) > power(peak)) peak = k;
        double delta = 0;
        if (peak < SIZE / 2) {
            double a = Math.log(Math.max(1e-24, power(peak - 1)));
            double b = Math.log(Math.max(1e-24, power(peak)));
            double c = Math.log(Math.max(1e-24, power(peak + 1))), denominator = a - 2 * b + c;
            delta = Math.abs(denominator) < 1e-9 ? 0 : Math.max(-.5, Math.min(.5, .5 * (a - c) / denominator));
        }
        frame.dominantHz = frame.rmsDb > -90 && bins[peak] > -85 ? (float)((peak + delta) * frame.binHz) : 0;
        int waveSamples = Math.min(SIZE / 4, Math.round(rate * .012f));
        int end = SIZE - waveSamples - 1;
        int begin = Math.max(1, end - waveSamples);
        int trigger = end;
        for (int i = begin; i <= end; i++) {
            if (ring[(write + i - 1) & (SIZE - 1)] - mean <= 0 && ring[(write + i) & (SIZE - 1)] - mean > 0) { trigger = i; break; }
        }
        frame.waveMs = waveSamples * 1000f / rate;
        for (int i = 0; i < frame.wave.length; i++) {
            double position = trigger + i * (waveSamples - 1.0) / (frame.wave.length - 1);
            int index = (int)position; double t = position - index;
            double x = ring[(write + index) & (SIZE - 1)], y = ring[(write + index + 1) & (SIZE - 1)];
            frame.wave[i] = (float)(x + (y - x) * t - mean);
        }
        frame.sequence++; ready = true;
    }

    private double power(int bin) { return re[bin] * re[bin] + im[bin] * im[bin]; }
    private static float db(double x) { return (float)Math.max(-120, 20 * Math.log10(Math.max(x, 1e-6))); }
}
