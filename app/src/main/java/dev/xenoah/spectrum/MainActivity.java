package dev.xenoah.spectrum;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import dev.xenoah.spectrum.core.HudState;

public final class MainActivity extends Activity {
    private static final int MIC_PERMISSION = 10;
    private final HudState state = new HudState();
    private AudioEngine audio;
    private HudView hud;
    private SharedPreferences prefs;
    private boolean resumed, focused, requesting, capturing;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("spectrum", MODE_PRIVATE);
        state.mode = bounded(prefs.getInt("mode", 0), 3);
        state.input = HudState.restoreInput(prefs.getInt("input", 0));
        state.level = bounded(prefs.getInt("level", 0), 4);
        audio = new AudioEngine(this);
        hud = new HudView(this, state, this);
        // Create content before touching the decor view (avoids the previous HUD launch failure).
        setContentView(hud);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
    }

    private int bounded(int value, int count) { return Math.max(0, Math.min(count - 1, value)); }

    @Override protected void onResume() {
        super.onResume(); resumed = true; immersive(); hud.startUpdates();
        focused = hasWindowFocus();
        syncInput(false);
    }

    @Override protected void onPause() {
        resumed = false;
        stopInput();
        if (hud != null) hud.stopUpdates();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (audio != null) audio.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        focused = focus;
        if (hud == null) return;
        if (focus) immersive();
        // Assistant overlays may take focus without pausing this Activity.
        // Release capture for those overlays, and resume only if the user did not select HOLD.
        syncInput(false);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private boolean allowed() { return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED; }

    private void stopInput() {
        if (audio != null) audio.stop();
        capturing = false;
    }

    private void startInput() { syncInput(true); }

    private void syncInput(boolean restart) {
        if (!resumed || !focused || state.frozen || requesting) {
            stopInput();
            if (resumed && !focused && !state.frozen && !requesting) {
                state.status = "WAITING"; state.detail = "Assistant / another window is active.";
                state.haveFrame = false; state.source = ""; state.diagnostic = "Microphone released. Return to resume.";
                hud.invalidate();
            }
            return;
        }
        if (capturing && !restart) return;
        stopInput(); state.haveFrame = false; state.clearHistory();
        state.source = ""; state.diagnostic = "";
        if (state.input != 4 && !allowed()) {
            state.status = "PERMISSION"; state.detail = "Microphone permission is required."; state.source = "";
            if (!prefs.getBoolean("askedMic", false) && !requesting) {
                hud.post(new Runnable() { @Override public void run() {
                    if (resumed && focused && !state.frozen && state.input != 4 && !allowed() && !requesting) requestMic();
                } });
            }
            hud.invalidate(); return;
        }
        state.status = "STARTING"; state.detail = state.input == 4 ? "Preparing generated test signal..." : "Opening microphone...";
        audio.start(state.input); capturing = true; hud.invalidate();
    }

    private void requestMic() {
        if (requesting || !resumed || !focused) return;
        boolean previouslyAsked = prefs.getBoolean("askedMic", false);
        if (previouslyAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                return;
            } catch (RuntimeException ignored) { }
        }
        stopInput(); requesting = true; prefs.edit().putBoolean("askedMic", true).apply();
        try { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION); }
        catch (RuntimeException unavailable) { requesting = false; state.status = "PERMISSION"; hud.invalidate(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != MIC_PERMISSION) return;
        requesting = false;
        if (allowed()) syncInput(false);
        else { state.status = "PERMISSION"; state.detail = "Microphone permission is required."; hud.invalidate(); }
    }

    void update() { audio.poll(state); }

    void tap() {
        if (state.help) { state.help = false; hud.invalidate(); return; }
        if (state.menu) { activateMenu(); return; }
        if (state.status.equals("PERMISSION")) { if (allowed()) startInput(); else requestMic(); return; }
        if (state.status.equals("MIC ERROR") || state.status.equals("NO SIGNAL") || state.status.equals("MIC MUTED")) { startInput(); return; }
        state.frozen = !state.frozen;
        if (state.frozen) stopInput(); else startInput();
        hud.invalidate();
    }

    void swipe(int delta) {
        if (state.help) return;
        if (state.menu) state.menuIndex = (state.menuIndex + delta + HudState.MENU_ITEMS) % HudState.MENU_ITEMS;
        else { state.mode = (state.mode + delta + 3) % 3; save(); }
        hud.invalidate();
    }

    void back() {
        if (state.help) state.help = false;
        else if (state.menu) state.menu = false;
        else { state.menu = true; state.menuIndex = 0; }
        hud.invalidate();
    }

    void clearPeaks() {
        audio.resetPeaks(); state.clearHistory();
        System.arraycopy(state.frame.bands, 0, state.frame.peaks, 0, state.frame.peaks.length);
        hud.invalidate();
    }

    void touch(float x, float y) {
        if (state.help) { tap(); return; }
        if (state.menu) {
            int item = (int)((y - 56) / 34);
            if (y >= 56 && item >= 0 && item < HudState.MENU_ITEMS) { state.menuIndex = item; activateMenu(); }
            else back();
        } else if (y >= 365 && x > 335) back();
        else if (y > 43 && y < 76) { state.mode = bounded((int)((x - 20) / 148), 3); save(); hud.invalidate(); }
        else tap();
    }

    private void activateMenu() {
        switch (state.menuIndex) {
            case 0: state.menu = false; break;
            case 1: state.mode = (state.mode + 1) % 3; break;
            case 2: state.level = (state.level + 1) % 4; break;
            case 3:
                state.input = (state.input + 1) % HudState.INPUTS.length;
                state.frozen = false; startInput(); break;
            case 4:
                state.menu = false; state.frozen = false;
                if (state.input != 4 && !allowed()) requestMic(); else startInput(); break;
            case 5: clearPeaks(); break;
            case 6: state.help = true; break;
            case 7: finish(); return;
            default: break;
        }
        save(); hud.invalidate();
    }

    private void save() { prefs.edit().putInt("mode", state.mode).putInt("level", state.level).putInt("input", state.input).apply(); }

    @Override public void onBackPressed() { back(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN, up = event.getAction() == KeyEvent.ACTION_UP;
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_SPACE:
                if (up && !event.isCanceled()) {
                    if (event.getEventTime() - event.getDownTime() >= 650) clearPeaks(); else tap();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && event.getRepeatCount() == 0) swipe(1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_UP:
                if (down && event.getRepeatCount() == 0) swipe(-1); return true;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU: case KeyEvent.KEYCODE_ESCAPE:
                if (up && !event.isCanceled()) back(); return true;
            case KeyEvent.KEYCODE_CAMERA:
                if (up && !event.isCanceled()) clearPeaks(); return true;
            default: return super.dispatchKeyEvent(event);
        }
    }
}
