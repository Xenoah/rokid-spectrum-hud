#!/usr/bin/env python3
"""Run the actual AudioEngine against fault-injectable framework doubles.
This tests lifecycle/error logic. It does NOT claim Android or physical-mic validation.
"""
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'build'/'audio-test';SRC=BASE/'src';CLASSES=BASE/'classes'
STUBS={
'android/content/Context.java': '''package android.content; public class Context { public static final String AUDIO_SERVICE="audio"; public Object getSystemService(String key){return new android.media.AudioManager();} }''',
'android/os/Build.java': '''package android.os; public class Build { public static class VERSION { public static int SDK_INT=31; } }''',
'android/os/Process.java': '''package android.os; public class Process { public static final int THREAD_PRIORITY_AUDIO=-16; public static void setThreadPriority(int x){} }''',
'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/100000L;} }''',
'android/util/Log.java': '''package android.util; public class Log { public static int w(String a,String b,Throwable t){return 0;} public static int e(String a,String b,Throwable t){return 0;} }''',
'android/media/AudioFormat.java': '''package android.media; public class AudioFormat { public static final int CHANNEL_IN_MONO=16,ENCODING_PCM_16BIT=2; }''',
'android/media/MediaRecorder.java': '''package android.media; public class MediaRecorder { public static class AudioSource {public static final int UNPROCESSED=9,VOICE_RECOGNITION=6,MIC=1;} }''',
'android/media/AudioDeviceInfo.java': '''package android.media; public class AudioDeviceInfo { public static final int TYPE_BUILTIN_MIC=15; public int getType(){return 15;} }''',
'android/media/AudioManager.java': '''package android.media; public class AudioManager { public static final int GET_DEVICES_INPUTS=1; public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED="raw"; public String getProperty(String k){return "true";} public AudioDeviceInfo[] getDevices(int x){return new AudioDeviceInfo[]{new AudioDeviceInfo()};} }''',
'android/media/AudioRecordingConfiguration.java': '''package android.media; public class AudioRecordingConfiguration { public boolean isClientSilenced(){return AudioRecord.silenced;} }''',
'android/media/audiofx/AudioEffect.java': '''package android.media.audiofx; public class AudioEffect { public int setEnabled(boolean x){return 0;} public void release(){} }''',
'android/media/AudioRecord.java': '''package android.media;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1;
 public static volatile boolean denied,failRaw,silenceRaw,silenced,readError;
 public static volatile int onlyRate,chunk=2048;
 public static final AtomicInteger open=new AtomicInteger(),maxOpen=new AtomicInteger(),created=new AtomicInteger();
 final int source,rate; long sample; boolean released;
 public AudioRecord(int source,int rate,int channel,int format,int bytes){
   if(denied)throw new SecurityException("permission denied");
   if((failRaw&&source==9)||(onlyRate!=0&&onlyRate!=rate))throw new IllegalArgumentException("unsupported input");
   this.source=source;this.rate=rate;int current=open.incrementAndGet();maxOpen.accumulateAndGet(current,Math::max);created.incrementAndGet();
 }
 public static int getMinBufferSize(int a,int b,int c){return 2048;}
 public int getState(){return 1;} public void startRecording(){} public int getRecordingState(){return 3;}
 public void stop(){} public void release(){if(!released){released=true;open.decrementAndGet();}}
 public int getAudioSessionId(){return 1;} public int getSampleRate(){return rate;}
 public boolean setPreferredDevice(AudioDeviceInfo d){return true;}
 public AudioRecordingConfiguration getActiveRecordingConfiguration(){return new AudioRecordingConfiguration();}
 public int read(short[] data,int offset,int count,int mode){
   if(readError)return -6;int n=Math.min(chunk,count);
   for(int i=0;i<n;i++,sample++)data[offset+i]=(silenceRaw&&source==9)?0:(short)(15000*Math.sin(2*Math.PI*1000*sample/rate));
   try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   return n;
 }
 public static void reset(){denied=false;failRaw=false;silenceRaw=false;silenced=false;readError=false;onlyRate=0;chunk=2048;created.set(0);maxOpen.set(0);}
}'''
}
for name in ['AutomaticGainControl','NoiseSuppressor','AcousticEchoCanceler']:
    STUBS[f'android/media/audiofx/{name}.java']=f'''package android.media.audiofx; public class {name} extends AudioEffect {{ public static boolean isAvailable(){{return true;}} public static {name} create(int x){{return new {name}();}} }}'''
STUBS['dev/xenoah/spectrum/AudioLifecycleTests.java']='''package dev.xenoah.spectrum;
import android.media.AudioRecord;
import android.content.Context;
import dev.xenoah.spectrum.core.HudState;

public class AudioLifecycleTests {
 interface Test {void run(AudioEngine audio,HudState ui)throws Exception;}
 static int count;
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void until(AudioEngine audio,HudState h,String status)throws Exception{
   long end=System.nanoTime()+3_000_000_000L;
   while(System.nanoTime()<end){audio.poll(h);if(h.status.equals(status))return;Thread.sleep(3);}
   throw new AssertionError("Expected "+status+"; got "+h.status+" "+h.detail);
 }
 static void test(String name,Test test)throws Exception{
   AudioRecord.reset();AudioEngine audio=new AudioEngine(new Context());HudState ui=new HudState();
   try{test.run(audio,ui);}finally{audio.close();long end=System.nanoTime()+1_000_000_000L;while(AudioRecord.open.get()!=0&&System.nanoTime()<end)Thread.sleep(3);}
   check(AudioRecord.open.get()==0,"recording resource leaked");count++;System.out.println("PASS "+name);
 }
 public static void main(String[] args)throws Exception{
   test("real engine delivers PCM-derived FFT snapshots",(a,h)->{a.start(0);until(a,h,"LIVE");check(h.haveFrame,"missing frame");check(Math.abs(h.frame.dominantHz-1000)<.2,"frequency");});
   test("unsupported RAW falls back to VOICE",(a,h)->{AudioRecord.failRaw=true;a.start(0);until(a,h,"LIVE");check(h.source.contains("VOICE"),"source fallback");});
   test("all-zero RAW stream falls back without showing fake LIVE",(a,h)->{AudioRecord.silenceRaw=true;a.start(0);until(a,h,"LIVE");check(h.source.contains("VOICE"),"zero-source fallback");});
   test("sample-rate fallback adapts the plotted Nyquist limit",(a,h)->{AudioRecord.onlyRate=16000;a.start(0);until(a,h,"LIVE");check(h.frame.sampleRate==16000&&h.frame.maxFrequency==8000,"rate adaptation");});
   test("short AudioRecord reads accumulate correctly",(a,h)->{AudioRecord.chunk=137;a.start(0);until(a,h,"LIVE");check(Math.abs(h.frame.dominantHz-1000)<.2,"partial reads");});
   test("permission denial becomes an actionable state",(a,h)->{AudioRecord.denied=true;a.start(0);until(a,h,"PERMISSION");check(!h.haveFrame,"permission fabricated a frame");});
   test("Android-silenced capture is reported explicitly",(a,h)->{AudioRecord.silenced=true;a.start(0);until(a,h,"MIC BUSY");});
   test("dead input returns MIC ERROR after candidate exhaustion",(a,h)->{AudioRecord.readError=true;a.start(0);until(a,h,"MIC ERROR");});
   test("rapid pause/restart never opens simultaneous recorders",(a,h)->{a.start(0);until(a,h,"LIVE");for(int i=0;i<30;i++){a.stop();a.start(i%4);}until(a,h,"LIVE");check(AudioRecord.maxOpen.get()<=1,"overlapping recorders");});
   test("DEMO uses generated samples with zero microphone opens",(a,h)->{a.start(4);until(a,h,"DEMO");long end=System.nanoTime()+2_000_000_000L;while(!h.haveFrame&&System.nanoTime()<end){a.poll(h);Thread.sleep(3);}check(h.haveFrame,"demo missing");check(AudioRecord.created.get()==0,"demo opened microphone");check(h.source.equals("DEMO / GENERATED"),"demo unlabeled");});
   System.out.println("RESULT "+count+" audio lifecycle tests passed (framework doubles; not device execution)");
 }
}'''
for relative,content in STUBS.items():
    path=SRC/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8')
CLASSES.mkdir(parents=True,exist_ok=True)
sources=list(SRC.rglob('*.java'))+list((ROOT/'core'/'src'/'main'/'java').rglob('*.java'))+[ROOT/'app/src/main/java/dev/xenoah/spectrum/AudioEngine.java']
subprocess.run(['java','com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(CLASSES),*[str(p)for p in sources]],check=True)
subprocess.run(['java','-cp',str(CLASSES),'dev.xenoah.spectrum.AudioLifecycleTests'],check=True)
