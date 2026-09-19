#!/usr/bin/env python3
"""Run the real Activity + AudioEngine with framework doubles for assistant overlays.
This validates event ordering, permissions, and user HOLD; not physical Rokid behavior.
"""
from pathlib import Path
import subprocess
from test_audio_lifecycle import STUBS as AUDIO_STUBS

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'build' / 'activity-test'
SRC, CLASSES = BASE / 'src', BASE / 'classes'
STUBS = dict(AUDIO_STUBS)
STUBS.update({
'android/Manifest.java': '''package android; public class Manifest { public static class permission { public static final String RECORD_AUDIO="mic"; } }''',
'android/content/pm/PackageManager.java': '''package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }''',
'android/os/Bundle.java': '''package android.os; public class Bundle {}''',
'android/net/Uri.java': '''package android.net; public class Uri { public static Uri parse(String s){return new Uri();} }''',
'android/content/Intent.java': '''package android.content; public class Intent { public Intent(String a,android.net.Uri u){} }''',
'android/provider/Settings.java': '''package android.provider; public class Settings { public static final String ACTION_APPLICATION_DETAILS_SETTINGS="settings"; }''',
'android/content/SharedPreferences.java': '''package android.content;
public class SharedPreferences {
 final java.util.Map<String,Object> data=new java.util.HashMap<>();
 public int getInt(String k,int d){return data.containsKey(k)?(Integer)data.get(k):d;}
 public boolean getBoolean(String k,boolean d){return data.containsKey(k)?(Boolean)data.get(k):d;}
 public Editor edit(){return new Editor();}
 public class Editor {public Editor putInt(String k,int v){data.put(k,v);return this;} public Editor putBoolean(String k,boolean v){data.put(k,v);return this;} public void apply(){} }
}''',
'android/view/View.java': '''package android.view; public class View {
 public static final int SYSTEM_UI_FLAG_FULLSCREEN=1,SYSTEM_UI_FLAG_HIDE_NAVIGATION=2,SYSTEM_UI_FLAG_IMMERSIVE_STICKY=4,SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN=8,SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION=16,SYSTEM_UI_FLAG_LAYOUT_STABLE=32;
 public void setSystemUiVisibility(int n){} public void invalidate(){} public boolean post(Runnable r){r.run();return true;}
}''',
'android/view/WindowManager.java': '''package android.view; public interface WindowManager { class LayoutParams { public static final int FLAG_KEEP_SCREEN_ON=128; } }''',
'android/view/Window.java': '''package android.view; public class Window { final View view=new View(); public View getDecorView(){return view;} public void addFlags(int n){} }''',
'android/view/KeyEvent.java': '''package android.view; public class KeyEvent {
 public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_ENTER=66,KEYCODE_DPAD_CENTER=23,KEYCODE_SPACE=62,KEYCODE_HEADSETHOOK=79,KEYCODE_MEDIA_PLAY_PAUSE=85,KEYCODE_DPAD_RIGHT=22,KEYCODE_DPAD_DOWN=20,KEYCODE_DPAD_LEFT=21,KEYCODE_DPAD_UP=19,KEYCODE_BACK=4,KEYCODE_MENU=82,KEYCODE_ESCAPE=111,KEYCODE_CAMERA=27,KEYCODE_ASSIST=219,KEYCODE_VOICE_ASSIST=231;
 final int action,code; public KeyEvent(int a,int c){action=a;code=c;}
 public int getKeyCode(){return code;} public int getAction(){return action;} public int getRepeatCount(){return 0;}
 public boolean isCanceled(){return false;} public long getEventTime(){return 100;} public long getDownTime(){return 90;}
}''',
'android/app/Activity.java': '''package android.app;
public class Activity extends android.content.Context {
 public static final int MODE_PRIVATE=0; public boolean testPermission=true,testFocus; public int testRequests,testForwarded;
 public final android.content.SharedPreferences testPrefs=new android.content.SharedPreferences(); final android.view.Window window=new android.view.Window();
 public void onCreate(android.os.Bundle b){} protected void onResume(){} protected void onPause(){} protected void onDestroy(){}
 public void onWindowFocusChanged(boolean f){testFocus=f;} public boolean hasWindowFocus(){return testFocus;}
 public android.content.SharedPreferences getSharedPreferences(String n,int m){return testPrefs;}
 public void setContentView(android.view.View v){} public android.view.Window getWindow(){return window;}
 public int checkSelfPermission(String p){return testPermission?0:-1;} public boolean shouldShowRequestPermissionRationale(String p){return true;}
 public void requestPermissions(String[] p,int n){testRequests++;} public void onRequestPermissionsResult(int c,String[] p,int[] r){}
 public String getPackageName(){return "dev.xenoah.rokidspectrum";} public void startActivity(android.content.Intent i){} public void finish(){}
 public void onBackPressed(){} public boolean dispatchKeyEvent(android.view.KeyEvent e){testForwarded++;return false;}
}''',
'dev/xenoah/spectrum/HudView.java': '''package dev.xenoah.spectrum; public class HudView extends android.view.View {
 public static dev.xenoah.spectrum.core.HudState state;
 public HudView(android.content.Context c,dev.xenoah.spectrum.core.HudState s,MainActivity a){state=s;}
 public void startUpdates(){} public void stopUpdates(){}
}''',
'dev/xenoah/spectrum/ActivityLifecycleTests.java': '''package dev.xenoah.spectrum;
import android.media.AudioRecord;
import android.view.KeyEvent;
import dev.xenoah.spectrum.core.HudState;

public class ActivityLifecycleTests {
 interface Test {void run(MainActivity a)throws Exception;}
 static int count;
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void live(MainActivity a)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){a.update();if(HudView.state.status.equals("LIVE")&&AudioRecord.open.get()==1)return;Thread.sleep(3);}throw new AssertionError("No LIVE capture: "+HudView.state.status);}
 static void released()throws Exception{long end=System.nanoTime()+1_000_000_000L;while(AudioRecord.open.get()!=0&&System.nanoTime()<end)Thread.sleep(3);check(AudioRecord.open.get()==0,"mic not released");}
 static void launch(MainActivity a)throws Exception{a.onCreate(null);a.onResume();a.onWindowFocusChanged(true);live(a);}
 static void test(String name,Test t)throws Exception{
   AudioRecord.reset();MainActivity a=new MainActivity();
   try{t.run(a);}finally{a.onPause();a.onDestroy();released();}
   count++;System.out.println("PASS "+name);
 }
 public static void main(String[] args)throws Exception{
   test("resume before focus waits, then starts one recorder",a->{a.onCreate(null);a.onResume();Thread.sleep(50);check(AudioRecord.created.get()==0,"mic opened without window focus");a.onWindowFocusChanged(true);live(a);a.onWindowFocusChanged(true);a.onResume();Thread.sleep(50);check(AudioRecord.created.get()==1,"duplicate start from focus/resume");});
   test("focus before resume starts only when both are ready",a->{a.onCreate(null);a.onWindowFocusChanged(true);check(AudioRecord.created.get()==0,"started paused activity");a.onResume();live(a);});
   test("assistant overlay releases mic without requiring onPause",a->{launch(a);a.onWindowFocusChanged(false);released();check(HudView.state.status.equals("WAITING"),"missing assistant wait");int before=AudioRecord.created.get();Thread.sleep(100);check(AudioRecord.created.get()==before,"captured under assistant overlay");a.onWindowFocusChanged(true);live(a);check(AudioRecord.created.get()==before+1,"return did not auto-resume");});
   test("assistant Activity pause and return recovers once",a->{launch(a);a.onPause();released();a.onWindowFocusChanged(false);a.onResume();check(AudioRecord.open.get()==0,"capture before assistant dismiss");a.onWindowFocusChanged(true);live(a);check(AudioRecord.created.get()==2,"wrong reopen count");});
   test("focus return while paused waits for onResume",a->{launch(a);a.onPause();released();int n=AudioRecord.created.get();a.onWindowFocusChanged(true);Thread.sleep(50);check(AudioRecord.created.get()==n,"resumed while Activity paused");a.onResume();live(a);});
   test("user HOLD survives assistant overlay and Activity pause",a->{launch(a);a.tap();released();check(HudView.state.frozen,"not held");int n=AudioRecord.created.get();a.onWindowFocusChanged(false);a.onPause();a.onResume();a.onWindowFocusChanged(true);Thread.sleep(70);check(HudView.state.frozen&&AudioRecord.created.get()==n,"auto-resume overrode HOLD");a.tap();live(a);});
   test("permission dialog holds capture until permission AND focus return",a->{a.testPermission=false;a.onCreate(null);a.onResume();a.onWindowFocusChanged(true);check(a.testRequests==1,"missing permission request");a.onWindowFocusChanged(false);a.testPermission=true;a.onRequestPermissionsResult(10,new String[]{"mic"},new int[]{0});Thread.sleep(50);check(AudioRecord.created.get()==0,"captured before permission dialog closed");a.onWindowFocusChanged(true);live(a);check(AudioRecord.created.get()==1,"multiple opens after grant");});
   test("permission callback after focus does not start twice",a->{a.testPermission=false;a.onCreate(null);a.onResume();a.onWindowFocusChanged(true);a.onWindowFocusChanged(false);a.testPermission=true;a.onWindowFocusChanged(true);check(AudioRecord.created.get()==0,"started while permission request pending");a.onRequestPermissionsResult(10,new String[]{"mic"},new int[]{0});live(a);check(AudioRecord.created.get()==1,"grant duplicate start");});
   test("denial does not reopen dialog or fabricate microphone input",a->{a.testPermission=false;a.onCreate(null);a.onResume();a.onWindowFocusChanged(true);a.onWindowFocusChanged(false);a.onRequestPermissionsResult(10,new String[]{"mic"},new int[]{-1});a.onWindowFocusChanged(true);Thread.sleep(60);check(a.testRequests==1&&AudioRecord.created.get()==0,"denial loop");check(HudView.state.status.equals("PERMISSION"),"denial not visible");});
   test("assistant and headset keys pass through to the system",a->{launch(a);for(int code:new int[]{KeyEvent.KEYCODE_ASSIST,KeyEvent.KEYCODE_VOICE_ASSIST,KeyEvent.KEYCODE_HEADSETHOOK,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE})for(int action:new int[]{0,1})check(!a.dispatchKeyEvent(new KeyEvent(action,code)),"reserved key consumed");check(a.testForwarded==8&&!HudView.state.frozen,"assistant key triggered HOLD");a.dispatchKeyEvent(new KeyEvent(1,KeyEvent.KEYCODE_ENTER));check(HudView.state.frozen,"normal analyzer Enter stopped working");});
   test("upgrading persisted CAM preference selects AUTO, not DEMO",a->{a.testPrefs.edit().putInt("input",5).apply();launch(a);check(HudView.state.input==0&&AudioRecord.privateStarts.get()==0,"unsafe preference migration");});
   test("rapid assistant focus changes never overlap recording sessions",a->{launch(a);for(int i=0;i<20;i++){a.onWindowFocusChanged(false);a.onWindowFocusChanged(true);}live(a);check(AudioRecord.maxOpen.get()==1,"overlapping AudioRecords");});
   test("tap on exhausted microphone checks retries instead of entering HOLD",a->{AudioRecord.silenced=true;a.onCreate(null);a.onResume();a.onWindowFocusChanged(true);long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){a.update();if(HudView.state.status.equals("MIC BLOCKED"))break;Thread.sleep(3);}check(HudView.state.status.equals("MIC BLOCKED"),"checks never finished");released();AudioRecord.silenced=false;a.tap();live(a);check(!HudView.state.frozen,"retry incorrectly entered HOLD");});
   test("tap on transient WAITING retries instead of freezing a blocked input",a->{launch(a);AudioRecord.silenced=true;long end=System.nanoTime()+1_000_000_000L;while(System.nanoTime()<end){a.update();if(HudView.state.status.equals("WAITING"))break;Thread.sleep(3);}check(HudView.state.status.equals("WAITING"),"missing waiting state");AudioRecord.silenced=false;a.tap();live(a);check(!HudView.state.frozen,"waiting tap froze the analyzer");});
   System.out.println("RESULT "+count+" Activity lifecycle tests passed (framework doubles; not device execution)");
 }
}'''
})

for relative, content in STUBS.items():
    path = SRC / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
CLASSES.mkdir(parents=True, exist_ok=True)
sources = list(SRC.rglob('*.java')) + list((ROOT / 'core/src/main/java').rglob('*.java'))
sources += [ROOT / 'app/src/main/java/dev/xenoah/spectrum' / name for name in ['AudioEngine.java', 'MainActivity.java']]
subprocess.run(['java', 'com.sun.tools.javac.Main', '-encoding', 'UTF-8', '-d', str(CLASSES), *map(str, sources)], check=True)
subprocess.run(['java', '-cp', str(CLASSES), 'dev.xenoah.spectrum.ActivityLifecycleTests'], check=True)
