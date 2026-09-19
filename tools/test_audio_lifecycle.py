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
'android/util/Log.java': '''package android.util; public class Log { public static int w(String a,String b){return 0;} public static int w(String a,String b,Throwable t){return 0;} public static int e(String a,String b,Throwable t){return 0;} }''',
'android/media/AudioFormat.java': '''package android.media; public class AudioFormat { public static final int CHANNEL_IN_MONO=16,ENCODING_PCM_16BIT=2; int rate,channel,encoding; public static class Builder {AudioFormat f=new AudioFormat(); public Builder setSampleRate(int n){f.rate=n;return this;} public Builder setChannelMask(int n){f.channel=n;return this;} public Builder setEncoding(int n){f.encoding=n;return this;} public AudioFormat build(){return f;} } }''',
'android/media/MediaRecorder.java': '''package android.media; public class MediaRecorder { public static class AudioSource {public static final int DEFAULT=0,UNPROCESSED=9,VOICE_RECOGNITION=6,MIC=1,CAMCORDER=5,VOICE_COMMUNICATION=7,VOICE_PERFORMANCE=10;} }''',
'android/media/AudioDeviceInfo.java': '''package android.media; public class AudioDeviceInfo { public static final int TYPE_BUILTIN_MIC=15; public int getType(){return 15;} }''',
'android/media/AudioManager.java': '''package android.media; public class AudioManager { public static final int GET_DEVICES_INPUTS=1,MODE_IN_CALL=2,MODE_IN_COMMUNICATION=3; public static final String PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED="raw"; public String getProperty(String k){return "true";} public AudioDeviceInfo[] getDevices(int x){return new AudioDeviceInfo[]{new AudioDeviceInfo()};} public boolean isMicrophoneMute(){return AudioRecord.systemMuted;} public int getMode(){return AudioRecord.audioMode;} }''',
'android/media/AudioRecordingConfiguration.java': '''package android.media; public class AudioRecordingConfiguration {private final AudioRecord r; AudioRecordingConfiguration(AudioRecord r){this.r=r;} public boolean isClientSilenced(){return AudioRecord.silenced||AudioRecord.blockedSource==r.source||AudioRecord.blockedRate==r.rate||(AudioRecord.pinBlocks&&r.pinned);} }''',
'android/media/audiofx/AudioEffect.java': '''package android.media.audiofx; public class AudioEffect { public int setEnabled(boolean x){android.media.AudioRecord.effectChanges.incrementAndGet();return 0;} public void release(){} }''',
'android/media/AudioRecord.java': '''package android.media;
import java.util.concurrent.atomic.AtomicInteger;
public class AudioRecord {
 public static final int STATE_INITIALIZED=1,RECORDSTATE_RECORDING=3,READ_NON_BLOCKING=1;
 public static volatile boolean denied,silenced,readError,systemMuted,allZero,pinBlocks,ignorePrivacyOverride;
 public static volatile int onlyRate,chunk=2048,failSource=-1,zeroSource=-1,blockedSource=-1,allowedSource=-1,audioMode,blockedRate;
 public static final AtomicInteger open=new AtomicInteger(),maxOpen=new AtomicInteger(),created=new AtomicInteger();
 public static final AtomicInteger privateStarts=new AtomicInteger(),effectChanges=new AtomicInteger(),nonprivateRequests=new AtomicInteger(),routePins=new AtomicInteger();
 final int source,rate; long sample; boolean released,privateCapture,pinned;
 public AudioRecord(int source,int rate,int channel,int format,int bytes){
   if(denied)throw new SecurityException("permission denied");
   if(failSource==source||(allowedSource>=0&&source!=allowedSource)||(onlyRate!=0&&onlyRate!=rate))throw new IllegalArgumentException("unsupported input");
   if(source==10&&android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("unguarded API 29 source");
   this.source=source;this.rate=rate;privateCapture=source==5||source==7;int current=open.incrementAndGet();maxOpen.accumulateAndGet(current,Math::max);created.incrementAndGet();
 }
 public static class Builder {
   int source,bufferSize; AudioFormat f; Boolean sensitive;
   public Builder setAudioSource(int n){source=n;return this;} public Builder setAudioFormat(AudioFormat n){f=n;return this;}
   public Builder setBufferSizeInBytes(int n){bufferSize=n;return this;}
   public Builder setPrivacySensitive(boolean n){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("unguarded API 30 call");sensitive=n;if(!n)nonprivateRequests.incrementAndGet();return this;}
   public AudioRecord build(){AudioRecord r=new AudioRecord(source,f.rate,f.channel,f.encoding,bufferSize);if(sensitive!=null&&!ignorePrivacyOverride)r.privateCapture=sensitive;return r;}
 }
 public static int getMinBufferSize(int a,int b,int c){return 2048;}
 public int getState(){return 1;} public void startRecording(){if(privateCapture)privateStarts.incrementAndGet();} public int getRecordingState(){return 3;}
 public void stop(){} public void release(){if(!released){released=true;open.decrementAndGet();}}
 public int getAudioSessionId(){return 1;} public int getSampleRate(){return rate;}
 public boolean setPreferredDevice(AudioDeviceInfo d){pinned=true;routePins.incrementAndGet();return true;}
 public AudioRecordingConfiguration getActiveRecordingConfiguration(){if(android.os.Build.VERSION.SDK_INT<29)throw new AssertionError("unguarded API 29 call");return new AudioRecordingConfiguration(this);}
 public boolean isPrivacySensitive(){if(android.os.Build.VERSION.SDK_INT<30)throw new AssertionError("unguarded API 30 query");return privateCapture;}
 public int read(short[] data,int offset,int count,int mode){
   if(readError)return -6;int n=Math.min(chunk,count);
   for(int i=0;i<n;i++,sample++)data[offset+i]=(allZero||source==zeroSource)?0:(short)(15000*Math.sin(2*Math.PI*1000*sample/rate));
   try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   return n;
 }
 public static void reset(){denied=false;silenced=false;readError=false;systemMuted=false;allZero=false;pinBlocks=false;ignorePrivacyOverride=false;blockedRate=0;onlyRate=0;chunk=2048;failSource=zeroSource=blockedSource=allowedSource=-1;audioMode=0;android.os.Build.VERSION.SDK_INT=31;created.set(0);maxOpen.set(0);privateStarts.set(0);effectChanges.set(0);nonprivateRequests.set(0);routePins.set(0);}
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
   test("policy-blocked nonzero queued PCM never becomes a measurement",(a,h)->{AudioRecord.silenced=true;a.start(0);until(a,h,"MIC BLOCKED");check(!h.haveFrame,"blocked samples were published as live");});
   test("dead input returns MIC ERROR after candidate exhaustion",(a,h)->{AudioRecord.readError=true;a.start(0);until(a,h,"MIC ERROR");});
   test("rapid pause/restart never opens simultaneous recorders",(a,h)->{a.start(0);until(a,h,"LIVE");for(int i=0;i<30;i++){a.stop();a.start(i%4);}until(a,h,"LIVE");check(AudioRecord.maxOpen.get()<=1,"overlapping recorders");});
   test("DEMO uses generated samples with zero microphone opens",(a,h)->{a.start(4);until(a,h,"DEMO");long end=System.nanoTime()+2_000_000_000L;while(!h.haveFrame&&System.nanoTime()<end){a.poll(h);Thread.sleep(3);}check(h.haveFrame,"demo missing");check(AudioRecord.created.get()==0,"demo opened microphone");check(h.source.equals("DEMO / GENERATED"),"demo unlabeled");});
   test("API 30 explicitly permits sharing without exclusive capture",(a,h)->{a.start(0);until(a,h,"LIVE");check(AudioRecord.nonprivateRequests.get()>0,"sharing request missing");check(AudioRecord.privateStarts.get()==0,"exclusive input started");check(h.source.startsWith("MIC"),"standard MIC must be first");});
   test("brief assistant interruption recovers the same input with fresh FFT history",(a,h)->{a.start(0);untilSource(a,h,"MIC");AudioRecord.blockedSource=1;until(a,h,"WAITING");long seq=h.frame.sequence;int created=AudioRecord.created.get();Thread.sleep(120);a.poll(h);check(h.frame.sequence==seq&&!h.haveFrame&&h.historyCount==0,"stale audio remained visible");check(AudioRecord.created.get()==created,"brief interruption restarted input");AudioRecord.blockedSource=-1;untilSource(a,h,"MIC");check(AudioRecord.created.get()==created,"resume reopened input");check(Math.abs(h.frame.dominantHz-1000)<.2,"invalid recovered FFT");});
   test("regression: initially live MIC later all-zero also switches",(a,h)->{a.start(0);untilSource(a,h,"MIC");AudioRecord.zeroSource=1;untilSource(a,h,"VOICE");});
   test("a transient startup block can recover before the next candidate",(a,h)->{AudioRecord.allowedSource=1;AudioRecord.silenced=true;a.start(0);openedAtLeast(1);int before=AudioRecord.created.get();AudioRecord.silenced=false;untilSource(a,h,"MIC");check(AudioRecord.created.get()==before,"recovery reopened the microphone unnecessarily");});
   test("all-busy AUTO ends with a finite result and releases every recorder",(a,h)->{AudioRecord.silenced=true;a.start(0);until(a,h,"MIC BLOCKED");int created=AudioRecord.created.get();check(created==9&&AudioRecord.open.get()==0,"scan did not finish/release");Thread.sleep(200);check(AudioRecord.created.get()==created,"scan loop never ended");check(h.inputSummary.contains("TESTED 9 / BLOCKED 9"),"missing scan result");AudioRecord.silenced=false;a.start(0);until(a,h,"LIVE");});
   test("system mute is respected before microphone creation",(a,h)->{AudioRecord.systemMuted=true;a.start(0);until(a,h,"MIC MUTED");check(AudioRecord.created.get()==0,"opened mic under system mute");AudioRecord.systemMuted=false;until(a,h,"LIVE");});
   test("system mute during capture resumes without changing settings",(a,h)->{a.start(0);until(a,h,"LIVE");AudioRecord.systemMuted=true;until(a,h,"MIC MUTED");Thread.sleep(100);check(AudioRecord.systemMuted,"app changed system mute");AudioRecord.systemMuted=false;until(a,h,"LIVE");});
   test("Android 10 never falls back to exclusive CAM capture",(a,h)->{android.os.Build.VERSION.SDK_INT=29;AudioRecord.allowedSource=5;a.start(0);until(a,h,"MIC ERROR");check(AudioRecord.created.get()==0,"exclusive CAM was opened");});
   test("Android 8 does not invoke newer policy APIs",(a,h)->{android.os.Build.VERSION.SDK_INT=26;a.start(0);until(a,h,"LIVE");});
   test("call mode is reported during checks without changing it",(a,h)->{AudioRecord.silenced=true;AudioRecord.audioMode=2;a.start(3);until(a,h,"SCANNING");check(h.inputSummary.contains("MODE 2"),"missing call detail");until(a,h,"MIC BLOCKED");check(AudioRecord.audioMode==2,"changed global audio mode");});
   test("legacy CAM selection safely resolves to non-private AUTO",(a,h)->{a.start(5);untilSource(a,h,"MIC");check(AudioRecord.privateStarts.get()==0,"legacy CAM blocked assistant");});
   test("all selectable real inputs allow sharing and preserve audio preprocessing",(a,h)->{for(int i=0;i<4;i++){a.start(i);until(a,h,"LIVE");}check(AudioRecord.privateStarts.get()==0,"exclusive input selected");check(AudioRecord.effectChanges.get()==0,"shared processing was modified");});
   test("regression: silenced first MIC input advances to usable VOICE",(a,h)->{AudioRecord.blockedSource=1;a.start(0);untilSource(a,h,"VOICE");check(AudioRecord.created.get()==2&&AudioRecord.maxOpen.get()==1,"did not release first input");});
   test("regression: 48 kHz initializes but is blocked; 16 kHz is actually tried",(a,h)->{AudioRecord.blockedRate=48000;a.start(0);until(a,h,"LIVE");check(h.frame.sampleRate==16000&&h.frame.maxFrequency==8000,"speech-rate route was never tested");});
   test("Android selects the route without forcing a vendor built-in device",(a,h)->{AudioRecord.pinBlocks=true;a.start(0);until(a,h,"LIVE");check(AudioRecord.routePins.get()==0,"forced an input device");});
   test("persistent mid-stream blocking advances after a grace period",(a,h)->{a.start(0);untilSource(a,h,"MIC");AudioRecord.blockedSource=1;until(a,h,"WAITING");untilSource(a,h,"VOICE");});
   test("all-zero streams terminate without a false LIVE result",(a,h)->{AudioRecord.allZero=true;a.start(0);until(a,h,"NO SIGNAL");check(!h.haveFrame&&AudioRecord.open.get()==0,"kept false or open input");check(h.inputSummary.contains("ZERO 9"),"missing zero-only result");});
   test("Android 11 communication route is explicitly non-private",(a,h)->{AudioRecord.allowedSource=7;a.start(0);untilSource(a,h,"COMM");check(AudioRecord.privateStarts.get()==0&&AudioRecord.audioMode==0,"private capture or global mode changed");});
   test("Android 11 camera route permits sharing before it starts",(a,h)->{AudioRecord.allowedSource=5;a.start(0);untilSource(a,h,"CAM");check(AudioRecord.privateStarts.get()==0,"private camera route started");});
   test("a device ignoring the non-private request is refused before recording",(a,h)->{AudioRecord.allowedSource=7;AudioRecord.ignorePrivacyOverride=true;a.start(0);until(a,h,"MIC ERROR");check(AudioRecord.privateStarts.get()==0&&AudioRecord.open.get()==0,"exclusive fallback escaped the guard");});
   test("Android 10 never selects private communication without an override",(a,h)->{android.os.Build.VERSION.SDK_INT=29;AudioRecord.allowedSource=7;a.start(0);until(a,h,"MIC ERROR");check(AudioRecord.privateStarts.get()==0&&AudioRecord.created.get()==0,"private communication on old API");});
   test("manual MIC selection tries two rates and remains bounded",(a,h)->{AudioRecord.silenced=true;a.start(3);until(a,h,"MIC BLOCKED");check(AudioRecord.created.get()==2&&AudioRecord.open.get()==0,"manual mode stuck or selected other sources");});
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
