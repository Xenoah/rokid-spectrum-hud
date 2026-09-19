package dev.xenoah.spectrum.core;

import java.util.Arrays;

/** UI-thread state shared by the Android view and offline renderer. */
public final class HudState {
    public static final String[] MODES = {"SPECTRUM", "WATERFALL", "WAVEFORM"};
    public static final String[] INPUTS = {"AUTO", "RAW", "VOICE", "MIC", "DEMO", "CAM"};
    public static final String[] LEVELS = {"AUTO", "0 dBFS", "-20 dBFS", "-40 dBFS"};
    public static final int HISTORY = 100, MENU_ITEMS = 8;
    public final SpectrumFrame frame = new SpectrumFrame();
    public final float[][] history = new float[HISTORY][SpectrumFrame.BANDS];
    public int mode, input, level, menuIndex, historyHead, historyCount;
    public boolean frozen, menu, help, haveFrame;
    public String status = "STARTING", detail = "Opening microphone...", source = "", diagnostic = "";
    public float topDb = -12;
    private int lastRate;
    private long lastSequence;

    public HudState() { clearHistory(); }

    public void clearHistory() {
        for (float[] row : history) Arrays.fill(row, -120);
        historyCount = 0; historyHead = 0; lastSequence = 0;
    }

    public void receive(SpectrumFrame next) {
        if (next.sampleRate != lastRate || (lastSequence > 0 && next.sequence <= lastSequence)) { clearHistory(); lastRate = next.sampleRate; }
        if (lastSequence > 0 && next.sequence > lastSequence + 1) {
            int missing = (int)Math.min(HISTORY, next.sequence - lastSequence - 1);
            for (int i = 0; i < missing; i++) {
                Arrays.fill(history[historyHead], -120);
                historyHead = (historyHead + 1) % HISTORY;
                historyCount = Math.min(HISTORY, historyCount + 1);
            }
        }
        lastSequence = next.sequence;
        frame.copyFrom(next); haveFrame = true;
        System.arraycopy(next.bands, 0, history[historyHead], 0, SpectrumFrame.BANDS);
        historyHead = (historyHead + 1) % HISTORY; historyCount = Math.min(HISTORY, historyCount + 1);
        float strongest = -120;
        for (float x : frame.bands) strongest = Math.max(strongest, x);
        float target = Math.max(-48, Math.min(0, (float)Math.ceil((strongest + 6) / 6) * 6));
        float dt = SpectrumAnalyzer.HOP / (float)Math.max(1, frame.sampleRate);
        topDb += (float)(1 - Math.exp(-dt / (target > topDb ? .08 : 1.8))) * (target - topDb);
    }

    public float ceiling() { return level == 0 ? topDb : -(level - 1) * 20f; }

    public String menuLabel(int index) {
        switch (index) {
            case 0: return "RETURN TO ANALYZER";
            case 1: return "VIEW     " + MODES[mode];
            case 2: return "SCALE    " + LEVELS[level];
            case 3: return "INPUT    " + INPUTS[input];
            case 4: return "RESTART MICROPHONE";
            case 5: return "CLEAR PEAKS / HISTORY";
            case 6: return "HELP / INPUT DETAILS";
            default: return "EXIT";
        }
    }
}
