package dev.xenoah.spectrum.core;

import java.util.Locale;

/** All coordinates are within 480 x 400. Pure green only, black is transparent on the optics. */
public final class HudRenderer {
    public interface Surface {
        void rect(float x, float y, float w, float h, int green);
        void line(float x1, float y1, float x2, float y2, float width, int green);
        void text(String text, float x, float baseline, float size, int green, boolean bold);
    }
    public static final float WIDTH = 480, HEIGHT = 400;
    private static final float LEFT = 48, RIGHT = 460, TOP = 134, BOTTOM = 306;

    public void draw(Surface s, HudState h) {
        s.rect(0, 0, WIDTH, HEIGHT, 0);
        if (h.help) { help(s, h); return; }
        if (h.menu) { menu(s, h); return; }
        title(s, "ROKID / SPECTRUM", h.frozen ? "HOLD" : h.status);
        for (int i = 0; i < 3; i++) {
            float x = 20 + i * 148;
            s.text(HudState.MODES[i], x, 63, 14, h.mode == i ? 255 : 130, h.mode == i);
            if (h.mode == i) s.line(x, 71, x + 114, 71, 2, 255);
        }
        if (!h.haveFrame || (!h.frozen && !h.status.equals("LIVE") && !h.status.equals("DEMO"))) {
            message(s, h);
            footer(s, h); return;
        }
        SpectrumFrame f = h.frame;
        s.text("DOMINANT", 20, 91, 11, 160, false);
        s.text(f.dominantHz > 0 ? hz(f.dominantHz) : "-- Hz", 20, 119, 26, 255, true);
        s.text("RMS dBFS", 220, 91, 11, 160, false);
        s.text(f.rmsDb <= -119 ? "< -120" : fmt("%.1f", f.rmsDb), 220, 117, 23, 235, true);
        s.text("PEAK dBFS", 350, 91, 11, 160, false);
        s.text(f.peakDb <= -119 ? "< -120" : fmt("%.1f", f.peakDb), 350, 117, 23, 235, true);
        if (h.mode == 0) spectrum(s, h);
        else if (h.mode == 1) waterfall(s, h);
        else waveform(s, h);
        s.text(h.source, 20, 352, 12, 180, false);
        String technical = fmt("%s kHz / FFT %d", trim(f.sampleRate / 1000f), SpectrumAnalyzer.SIZE);
        s.text(technical, 218, 352, 12, 180, false);
        if (f.clipping) s.text("CLIP", 418, 352, 12, 255, true);
        footer(s, h);
    }

    private void title(Surface s, String text, String status) {
        s.text(text, 20, 30, 19, 255, true);
        s.text(status, 346, 29, 14, 230, true);
        s.line(20, 41, 460, 41, 1, 135);
    }

    private void footer(Surface s, HudState h) {
        s.line(20, 365, 460, 365, 1, 110);
        s.text("TAP " + (h.frozen ? "RESUME" : "HOLD") + "   SWIPE VIEW   BACK MENU", 20, 385, 12, 220, false);
    }

    private void message(Surface s, HudState h) {
        s.text(h.status.equals("PERMISSION") ? "MICROPHONE ACCESS" : h.status, 24, 151, 24, 255, true);
        if (h.status.equals("PERMISSION")) {
            s.text("Allow microphone access to analyze sound.", 24, 185, 15, 220, false);
            s.text("Tap to request permission / open settings.", 24, 211, 15, 220, false);
            s.text("No audio is saved or sent.", 24, 253, 14, 170, false);
        } else if (h.status.equals("MIC ERROR") || h.status.equals("NO SIGNAL") || h.status.equals("MIC BUSY")) {
            s.text(h.detail, 24, 184, 14, 220, false);
            s.text("Tap to retry. BACK opens input settings.", 24, 213, 15, 220, false);
            s.text("Try INPUT: MIC or VOICE if AUTO fails.", 24, 251, 14, 170, false);
            s.text("DEMO checks the display without a mic.", 24, 279, 14, 170, false);
        } else {
            s.text(h.detail, 24, 191, 15, 220, false);
            s.text("Sound is processed on this device only.", 24, 236, 14, 170, false);
        }
    }

    private void spectrum(Surface s, HudState h) {
        float top = h.ceiling(), floor = top - 80;
        for (int i = 0; i <= 4; i++) {
            float y = TOP + i * (BOTTOM - TOP) / 4;
            s.line(LEFT, y, RIGHT, y, 1, i == 4 ? 145 : 65);
            s.text(fmt("%.0f", top - 20 * i), 13, y + 4, 11, 170, false);
        }
        float width = (RIGHT - LEFT) / SpectrumFrame.BANDS;
        for (int i = 0; i < SpectrumFrame.BANDS; i++) {
            float y = yDb(h.frame.bands[i], floor), p = yDb(h.frame.peaks[i], floor);
            float x = LEFT + width * i;
            if (y < BOTTOM) s.rect(x + 1, y, width - 2, BOTTOM - y, 208);
            if (p < BOTTOM) s.line(x + 1, p, x + width - 1, p, 2, 255);
        }
        frequencies(s, h.frame.maxFrequency);
        s.text(h.level == 0 ? "AUTO SCALE" : "FIXED SCALE", 328, 335, 10, 155, false);
        s.text("HANN / LOG Hz", 48, 335, 10, 155, false);
    }

    private void frequencies(Surface s, float max) {
        float[] frequencies = {20, 100, 1000, 10000};
        for (float f : frequencies) {
            if (f > max * .82f) continue;
            float x = frequencyX(f, max);
            s.line(x, BOTTOM, x, BOTTOM + 5, 1, 160);
            s.text(f >= 1000 ? fmt("%.0fk", f / 1000) : fmt("%.0f", f), x - 7, BOTTOM + 20, 12, 205, false);
        }
        s.text(fmt("%.0fk", max / 1000), RIGHT - 22, BOTTOM + 20, 12, 205, false);
    }

    private void waterfall(Surface s, HudState h) {
        float floor = h.ceiling() - 80, w = (RIGHT - LEFT) / SpectrumFrame.BANDS, rh = (BOTTOM - TOP) / HudState.HISTORY;
        for (int age = 0; age < h.historyCount; age++) {
            int row = (h.historyHead - 1 - age + HudState.HISTORY) % HudState.HISTORY;
            for (int b = 0; b < SpectrumFrame.BANDS; b++) {
                float value = clamp((h.history[row][b] - floor) / 80, 0, 1);
                int green = value < .08 ? 0 : (int)(255 * Math.pow(value, .85));
                if (green > 0) s.rect(LEFT + b * w, TOP + age * rh, w + .25f, rh + .25f, green);
            }
        }
        s.text("0s", 15, TOP + 5, 11, 180, false);
        float duration = HudState.HISTORY * SpectrumAnalyzer.HOP / (float)h.frame.sampleRate;
        s.text(fmt("%.1f", duration), 13, BOTTOM, 11, 180, false);
        s.line(LEFT, BOTTOM, RIGHT, BOTTOM, 1, 145);
        frequencies(s, h.frame.maxFrequency);
        s.text("NEWEST AT TOP", 48, 335, 10, 155, false);
        s.text("BRIGHTER = LOUDER", 297, 335, 10, 155, false);
    }

    private void waveform(Surface s, HudState h) {
        float mid = (TOP + BOTTOM) / 2, amplitude = 0;
        for (float v : h.frame.wave) amplitude = Math.max(amplitude, Math.abs(v));
        float fullScale = h.level == 0 ? Math.max(.001f, amplitude * 1.12f) : (float)Math.pow(10, h.ceiling() / 20);
        for (int i = 0; i <= 4; i++) {
            float y = TOP + (BOTTOM - TOP) * i / 4;
            s.line(LEFT, y, RIGHT, y, 1, i == 2 ? 155 : 65);
        }
        for (int i = 0; i <= 4; i++) {
            float x = LEFT + (RIGHT - LEFT) * i / 4;
            s.line(x, TOP, x, BOTTOM, 1, 65);
        }
        s.text("+", 25, TOP + 6, 12, 180, false); s.text("0", 25, mid + 4, 12, 180, false); s.text("-", 25, BOTTOM, 12, 180, false);
        for (int i = 1; i < h.frame.wave.length; i++) {
            float x0 = LEFT + (i - 1) * (RIGHT - LEFT) / (h.frame.wave.length - 1);
            float x1 = LEFT + i * (RIGHT - LEFT) / (h.frame.wave.length - 1);
            float y0 = mid - clamp(h.frame.wave[i - 1] / fullScale, -1, 1) * (BOTTOM - TOP) / 2;
            float y1 = mid - clamp(h.frame.wave[i] / fullScale, -1, 1) * (BOTTOM - TOP) / 2;
            s.line(x0, y0, x1, y1, 1.7f, 255);
        }
        s.text("0 ms", LEFT - 7, BOTTOM + 20, 12, 205, false);
        s.text(fmt("%.1f ms", h.frame.waveMs), RIGHT - 53, BOTTOM + 20, 12, 205, false);
        s.text(fmt("SPAN +/- %.4f FS", fullScale), 48, 335, 10, 155, false);
        s.text(h.level == 0 ? "AUTO SCALE" : "FIXED SCALE", 328, 335, 10, 155, false);
    }

    private void menu(Surface s, HudState h) {
        title(s, "ANALYZER / MENU", h.frozen ? "HOLD" : h.status);
        for (int i = 0; i < HudState.MENU_ITEMS; i++) {
            float y = 56 + i * 34;
            if (i == h.menuIndex) { s.rect(20, y, 440, 30, 50); s.rect(20, y, 3, 30, 255); }
            s.text(h.menuLabel(i), 33, y + 21, 16, i == h.menuIndex ? 255 : 190, i == h.menuIndex);
        }
        s.line(20, 343, 460, 343, 1, 100);
        s.text("SWIPE SELECT / TAP CHANGE / BACK RETURN", 20, 371, 12, 220, false);
        s.text("Input changes take effect immediately.", 20, 391, 11, 150, false);
    }

    private void help(Surface s, HudState h) {
        title(s, "HELP / INPUT", "v1.0.0");
        String[] lines = {
            "Tap: hold / resume microphone",
            "Swipe: spectrum / waterfall / waveform",
            "Back (double tap): menu / return",
            "Long tap or camera key: clear peaks",
            "Microphone only. No playback capture.",
            "dBFS is digital level, NOT sound-pressure dB.",
            "DEMO is synthetic, not a microphone reading.",
            "Source: " + h.source,
            h.haveFrame ? fmt("%.2f Hz/bin / %.0f ms window", h.frame.binHz, h.frame.windowMs) : "Waiting for a microphone frame."
        };
        for (int i = 0; i < lines.length; i++) s.text(lines[i], 22, 72 + i * 29, 14, i < 4 ? 235 : 185, false);
        s.line(20, 343, 460, 343, 1, 100);
        s.text("TAP / BACK TO RETURN", 20, 377, 14, 230, false);
    }

    private static float frequencyX(float f, float max) { return LEFT + (RIGHT - LEFT) * (float)(Math.log(f / 20) / Math.log(max / 20)); }
    private static float yDb(float db, float floor) { return BOTTOM - clamp((db - floor) / 80, 0, 1) * (BOTTOM - TOP); }
    private static float clamp(float x, float min, float max) { return Math.max(min, Math.min(max, x)); }
    private static String hz(float f) { return f >= 1000 ? fmt("%.2f kHz", f / 1000) : fmt("%.1f Hz", f); }
    private static String trim(float f) { return f == Math.round(f) ? fmt("%.0f", f) : fmt("%.1f", f); }
    private static String fmt(String f, Object... args) { return String.format(Locale.US, f, args); }
}
