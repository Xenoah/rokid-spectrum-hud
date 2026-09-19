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
'android/media/AudioFormat.java': '''package android.media; public class AudioFormat { public static final int CHANNEL_IN_MONO=16,ENCODING_PCM_16BIT=2; int rate,channel,encoding; public static class Builder {AudioFormat f=new AudioFormat(); public Builder setSampleRate(int n){f.rate=n;return this;} public Builder setChannelMask(int n){f.channel=n;return this;} public Builder setEncoding(int n){f.encoding=n;return this;} public AudioFormat build(){return f;} } }''',
'android/media/MediaRecorder.java': '''package android.media; public class MediaRecorder { public static class AudioSource {public static final int UNPROCESSED=9,VOICE_RECOGNITION=6,MIC=1,CAMCORDER=5;} }''',
'android/media/AudioDeviceInfo.java': '''package android.media; public class AudioDeviceInfo { public static final int TYPE_BUILTIN_MIC=15; public int getType(){return 15;} }''',
'android/media/AudioManager.java': '''package android.media; public class AudioManager { public static final int GET_DEVICES_INPUTS=1,MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3; public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED="raw"; public String getProperty(String k){return "true";} public AudioDeviceInfo[] getDevices(int x){return new AudioDeviceInfo[]{new AudioDeviceInfo()};} public boolean isMicrophoneMute(){return AudioRecord.systemMuted;} public int getMode(){return AudioRecord.audioMode;} }''',
'android/media/AudioRecordingConfiguration.java': '''package android.media; public class AudioRecordingConfiguration {private final AudioRecord r; AudioRecordingConfiguration(AudioRecord r){this.r=r;} public boolean isClientSilenced(){return AudioRecord.silenced||AudioRecord.blockedSource==r.source;} }''',
'android/media/audiofx/AudioEffect.java': '''package android.media.audiofx; public class AudioEffect { public int setEnabled(boolean x){android.media.AudioRecord.effectChanges.incrementAndGet();return 0;} public void release(){} }''',
'android/media/AudioRecord.java': '''package android.media;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1;
 public static volatile boolean denied,silenced,readError,systemMuted,allZero;
 public static volatile int onlyRate,chunk=2048,failSource=-1,zeroSource=-1,blockedSource=-1,allowedSource=-1,audioMode;
 public static final AtomicInteger open=new AtomicInteger(),maxOpen=new AtomicInteger(),created=new AtomicInteger();
 public static final AtomicInteger privateStarts=new AtomicInteger(),effectChanges=new AtomicInteger(),nonprivateRequests=new AtomicInteger();
 final int source,rate; long sample; boolean released,privateCapture;
 public AudioRecord(int source,int rate,int channel,int format,int bytes){
   if(denied)throw new SecurityException("permission denied");
   if(failSource==source||(allowedSource>=0&&source!=allowedSource)||(onlyRate!=0&&onlyRate!=rate))throw new IllegalArgumentException("unsupported input");
   this.source=source;this.rate=rate;privateCapture=source==5;int current=open.incrementAndGet();maxOpen.accumulateAndGet(current,Math::max);created.incrementAndGet();
 }
 public static class Builder {
   int source,bufferSize; AudioFormat f; Boolean sensitive;
   public Builder setAudioSource(int n){source=n;return this;} public Builder setAudioFormat(AudioFormat n){f=n;return this;}
   public Builder setBufferSizeInBytes(int n){bufferSize=n;return this;}
   public Builder setPrivacySensitive(boolean n){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("unguarded API 30 call");sensitive=n;if(!n)nonprivateRequests.incrementAndGet();return this;}
   public AudioRecord build(){AudioRecord r=new AudioRecord(source,f.rate,f.channel,f.encoding,bufferSize);if(sensitive!=null)r.privateCapture=sensitive;return r;}
 }
 public static int getMinBufferSize(int a,int b,int c){return 2048;}
 public int getState(){return 1;} public void startRecording(){if(privateCapture)privateStarts.incrementAndGet();} public int getRecordingState(){return 3;}
 public void stop(){} public void release(){if(!released){released=true;open.decrementAndGet();}}
 public int getAudioSessionId(){return 1;} public int getSampleRate(){return rate;}
 public boolean setPreferredDevice(AudioDeviceInfo d){return true;}
 public AudioRecordingConfiguration getActiveRecordingConfiguration(){if(android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("unguarded API 29 call");return new AudioRecordingConfiguration(this);}
 public boolean isPrivacySensitive(){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("unguarded API 30 query");return privateCapture;}
 public int read(short[] data,int offset,int count,int mode){
   if(readError)return -6;int n=Math.min(chunk,count);
   for(int i=0;i<n;i++,sample++)data[offset+i]=(allZero||source==zeroSource)?0:(short)(15000*Math.sin(2*Math.PI*1000*sample/rate));
   try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   return n;
 }
 public static void reset(){denied=false;silenced=false;readError=false;systemMuted=false;allZero=false;onlyRate=0;chunk=2048;failSource=zeroSource=blockedSource=allowedSource=-1;audioMode=0;android.os.Build.VERSION.SDK_INT=31;created.set(0);maxOpen.set(0);privateStarts.set(0);effectChanges.set(0);nonprivateRequests.set(0);}
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
 static void untilSource(AudioEngine audio,HudState h,String source)throws Exception{
   long end=System.nanoTime()+3_000_000_000L;
   while(System.nanoTime()<end){audio.poll(h);if(h.status.equals("LIVE")&&h.source.startsWith(source))return;Thread.sleep(3);}
   throw new AssertionError("Expected LIVE "+source+"; got "+h.status+" "+h.source);
 }
 static void openedAtLeast(int wanted)throws Exception{
   long end=System.nanoTime()+3_000_000_000L;
   while(AudioRecord.created.get()<wanted&&System.nanoTime()<end)Thread.sleep(3);
   check(AudioRecord.created.get()>=wanted,"input candidates were never retried");
 }
 static void test(String name,Test test)throws Exception{
   AudioRecord.reset();AudioEngine audio=new AudioEngine(new Context());HudState ui=new HudState();
   try{test.run(audio,ui);}finally{audio.close();long end=System.nanoTime()+1_000_000_000L;while(AudioRecord.open.get()!=0&&System.nanoTime()<end)Thread.sleep(3);}
   check(AudioRecord.open.get()==0,"recording resource leaked");count++;System.out.println("PASS "+name);
 }
 public static void main(String[] args)throws Exception{
   test("real engine delivers PCM-derived FFT snapshots",(a,h)->{a.start(0);until(a,h,"LIVE");check(h.haveFrame,"missing frame");check(Math.abs(h.frame.dominantHz-1000)<.2,"frequency");});
   test("unsupported MIC falls back to VOICE",(a,h)->{AudioRecord.failSource=1;a.start(0);until(a,h,"LIVE");check(h.source.contains("VOICE"),"source fallback");});
   test("all-zero MIC stream falls back without showing fake LIVE",(a,h)->{AudioRecord.zeroSource=1;a.start(0);until(a,h,"LIVE");check(h.source.contains("VOICE"),"zero-source fallback");});
   test("sample-rate fallback adapts the plotted Nyquist limit",(a,h)->{AudioRecord.onlyRate=16000;a.start(0);until(a,h,"LIVE");check(h.frame.sampleRate==16000&&h.frame.maxFrequency==8000,"rate adaptation");});
   test("short AudioRecord reads accumulate correctly",(a,h)->{AudioRecord.chunk=137;a.start(0);until(a,h,"LIVE");check(Math.abs(h.frame.dominantHz-1000)<.2,"partial reads");});
   test("permission denial becomes an actionable state",(a,h)->{AudioRecord.denied=true;a.start(0);until(a,h,"PERMISSION");check(!h.haveFrame,"permission fabricated a frame");});
   test("Android-silenced capture waits even when queued PCM is nonzero",(a,h)->{AudioRecord.silenced=true;a.start(0);until(a,h,"WAITING");check(!h.haveFrame,"blocked samples were published as live");});
   test("dead input returns MIC ERROR after candidate exhaustion",(a,h)->{AudioRecord.readError=true;a.start(0);until(a,h,"MIC ERROR");});
   test("rapid pause/restart never opens simultaneous recorders",(a,h)->{a.start(0);until(a,h,"LIVE");for(int i=0;i<30;i++){a.stop();a.start(i%4);}until(a,h,"LIVE");check(AudioRecord.maxOpen.get()<=1,"overlapping recorders");});
   test("DEMO uses generated samples with zero microphone opens",(a,h)->{a.start(4);until(a,h,"DEMO");long end=System.nanoTime()+2_000_000_000L;while(!h.haveFrame&&System.nanoTime()<end){a.poll(h);Thread.sleep(3);}check(h.haveFrame,"demo missing");check(AudioRecord.created.get()==0,"demo opened microphone");check(h.source.equals("DEMO / GENERATED"),"demo unlabeled");});
   test("API 30 explicitly permits sharing without exclusive capture",(a,h)->{a.start(0);until(a,h,"LIVE");check(AudioRecord.nonprivateRequests.get()>0,"sharing request missing");check(AudioRecord.privateStarts.get()==0,"exclusive input started");check(h.source.startsWith("MIC"),"standard MIC must be first");});
   test("assistant priority mid-stream waits and resumes on the same input",(a,h)->{a.start(0);untilSource(a,h,"MIC");AudioRecord.blockedSource=1;until(a,h,"WAITING");long seq=h.frame.sequence;int created=AudioRecord.created.get();Thread.sleep(350);a.poll(h);check(h.frame.sequence==seq,"blocked audio was published");check(AudioRecord.created.get()==created,"app competed by switching input");AudioRecord.blockedSource=-1;untilSource(a,h,"MIC");check(AudioRecord.created.get()==created,"resume reopened input");check(Math.abs(h.frame.dominantHz-1000)<.2,"invalid recovered FFT");});
   test("regression: initially live MIC later all-zero also switches",(a,h)->{a.start(0);untilSource(a,h,"MIC");AudioRecord.zeroSource=1;untilSource(a,h,"VOICE");});
   test("assistant already active at launch can finish without a retry tap",(a,h)->{AudioRecord.allowedSource=1;AudioRecord.silenced=true;a.start(0);until(a,h,"WAITING");int before=AudioRecord.created.get();AudioRecord.silenced=false;untilSource(a,h,"MIC");check(AudioRecord.created.get()==before,"recovery reopened the microphone unnecessarily");});
   test("all-busy AUTO holds its first non-private input without scanning",(a,h)->{AudioRecord.silenced=true;a.start(0);until(a,h,"WAITING");Thread.sleep(350);check(AudioRecord.created.get()==1,"reopened input while assistant has priority");AudioRecord.silenced=false;until(a,h,"LIVE");});
   test("system mute is respected before microphone creation",(a,h)->{AudioRecord.systemMuted=true;a.start(0);until(a,h,"MIC MUTED");check(AudioRecord.created.get()==0,"opened mic under system mute");AudioRecord.systemMuted=false;until(a,h,"LIVE");});
   test("system mute during capture resumes without changing settings",(a,h)->{a.start(0);until(a,h,"LIVE");AudioRecord.systemMuted=true;until(a,h,"MIC MUTED");Thread.sleep(100);check(AudioRecord.systemMuted,"app changed system mute");AudioRecord.systemMuted=false;until(a,h,"LIVE");});
   test("Android 10 never falls back to exclusive CAM capture",(a,h)->{android.os.Build.VERSION.SDK_INT=29;AudioRecord.allowedSource=5;a.start(0);until(a,h,"MIC ERROR");check(AudioRecord.created.get()==0,"exclusive CAM was opened");});
   test("Android 8 does not invoke newer policy APIs",(a,h)->{android.os.Build.VERSION.SDK_INT=26;a.start(0);until(a,h,"LIVE");});
   test("call mode gives a distinct diagnostic without changing mode",(a,h)->{AudioRecord.silenced=true;AudioRecord.audioMode=2;a.start(3);until(a,h,"WAITING");check(h.detail.startsWith("Call"),"missing call detail");check(AudioRecord.audioMode==2,"changed global audio mode");});
   test("legacy CAM selection safely resolves to non-private AUTO",(a,h)->{a.start(5);untilSource(a,h,"MIC");check(AudioRecord.privateStarts.get()==0,"legacy CAM blocked assistant");});
   test("all selectable real inputs allow sharing and preserve audio preprocessing",(a,h)->{for(int i=0;i<4;i++){a.start(i);until(a,h,"LIVE");}check(AudioRecord.privateStarts.get()==0,"exclusive input selected");check(AudioRecord.effectChanges.get()==0,"shared processing was modified");});
   System.out.println("RESULT "+count+" audio lifecycle tests passed (framework doubles; not device execution)");
 }
}'''
def main():
    for relative,content in STUBS.items():
        path=SRC/relative;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8')
    CLASSES.mkdir(parents=True,exist_ok=True)
    sources=list(SRC.rglob('*.java'))+list((ROOT/'core'/'src'/'main'/'java').rglob('*.java'))+[ROOT/'app/src/main/java/dev/xenoah/spectrum/AudioEngine.java']
    subprocess.run(['java','com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(CLASSES),*[str(p)for p in sources]],check=True)
    subprocess.run(['java','-cp',str(CLASSES),'dev.xenoah.spectrum.AudioLifecycleTests'],check=True)

if __name__ == '__main__': main()
