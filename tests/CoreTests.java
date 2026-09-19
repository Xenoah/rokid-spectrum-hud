import dev.xenoah.spectrum.core.*;
import java.util.Arrays;
import java.util.Random;

/** Numeric tests against analytic signals and a slow direct DFT, not the implementation. */
public final class CoreTests {
    static int passed;
    interface Test { void run() throws Exception; }
    static void test(String name, Test t) throws Exception { t.run(); passed++; System.out.println("PASS " + name); }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static void near(double x, double y, double tolerance, String what) { check(Math.abs(x-y) <= tolerance, what + ": " + x + " vs " + y); }
    static SpectrumFrame analyze(int rate, short[] data, int chunk) {
        SpectrumAnalyzer a = new SpectrumAnalyzer(rate);
        SpectrumFrame out = new SpectrumFrame();
        for (int start = 0; start < data.length; start += chunk) {
            int count = Math.min(chunk, data.length - start);
            short[] part = Arrays.copyOfRange(data, start, start + count);
            a.accept(part, count, out::copyFrom);
        }
        return out;
    }
    static short[] sine(int rate, double frequency, double amplitude, int size) {
        short[] data = new short[size];
        for (int i=0; i<size; i++) data[i]=(short)Math.round(32767 * amplitude * Math.sin(2*Math.PI*frequency*i/rate));
        return data;
    }
    static float max(float[] x) { float m=-120; for(float v:x)m=Math.max(m,v); return m; }

    public static void main(String[] args) throws Exception {
        test("radix-2 FFT vs independent complex DFT", () -> {
            Random random=new Random(52);
            for(int n:new int[]{2,4,8,16,32,64}) {
                double[] r=new double[n],i=new double[n];
                for(int k=0;k<n;k++){r[k]=random.nextDouble()*2-1;i[k]=random.nextDouble()*2-1;}
                double[] originalR=r.clone(),originalI=i.clone();new Fft(n).transform(r,i);
                for(int k=0;k<n;k++){
                    double expectedR=0,expectedI=0;
                    for(int j=0;j<n;j++){
                        double a=-2*Math.PI*j*k/n;
                        expectedR+=originalR[j]*Math.cos(a)-originalI[j]*Math.sin(a);
                        expectedI+=originalR[j]*Math.sin(a)+originalI[j]*Math.cos(a);
                    }
                    near(r[k],expectedR,1e-9,"real");near(i[k],expectedI,1e-9,"imag");
                }
            }
        });
        test("FFT rejects non-power-of-two sizes", () -> {
            try {new Fft(13);throw new AssertionError("accepted invalid size");}catch(IllegalArgumentException expected){}
        });
        for(int rate:new int[]{48000,44100,32000,16000}) {
            final int fs=rate;
            test("half-scale bin-centred sine calibration at " + fs, () -> {
                double f=170.0*fs/SpectrumAnalyzer.SIZE;
                SpectrumFrame x=analyze(fs,sine(fs,f,.5,16384),1024);
                near(x.rmsDb,-9.0312,.003,"AC RMS dBFS");
                near(max(x.bands),-6.0206,.005,"Hann coherent-gain normalisation");
                near(x.dominantHz,f,.02,"frequency");
                check(!x.clipping,"false clipping");
                near(x.maxFrequency,Math.min(20000,fs/2),.01,"Nyquist cap");
            });
        }
        for(double frequency:new double[]{40,55,100,440,1000,3955,10000,19000}) {
            final double f=frequency;
            test("off-bin frequency " + f + " Hz", () -> {
                SpectrumFrame x=analyze(48000,sine(48000,f,.3,20000),257);
                near(x.dominantHz,f,.16,"quadratic log-spectrum interpolation");
                near(x.rmsDb,20*Math.log10(.3/Math.sqrt(2)),.08,"RMS");
            });
        }
        test("all-zero input is finite, silent, has no dominant frequency", () -> {
            SpectrumFrame x=analyze(48000,new short[12288],511);
            near(x.rmsDb,-120,0,"silence RMS");near(x.dominantHz,0,0,"silence pitch");
            for(float db:x.bands)near(db,-120,0,"silence spectrum");
            for(float v:x.wave)near(v,0,0,"silent waveform");
        });
        test("DC removed from spectrum and AC RMS", () -> {
            short[] data=new short[16384];Arrays.fill(data,(short)12345);SpectrumFrame x=analyze(48000,data,123);
            near(x.rmsDb,-120,0,"DC RMS");near(max(x.bands),-120,0,"DC spectrum");near(x.dominantHz,0,0,"DC frequency");
        });
        test("full-scale PCM detects clipping", () -> {
            short[] data=new short[16384];for(int i=0;i<data.length;i++)data[i]=(i%2==0)?Short.MAX_VALUE:Short.MIN_VALUE;
            SpectrumFrame x=analyze(16000,data,2048);check(x.clipping,"clip missing");near(x.peakDb,0,.001,"peak");
            near(x.dominantHz,8000,.01,"Nyquist frequency");
        });
        test("chunk boundaries do not affect output", () -> {
            short[] data=sine(48000,997,.25,65536);SpectrumFrame a=analyze(48000,data,1),b=analyze(48000,data,2048),c=analyze(48000,data,317);
            check(a.sequence==29&&b.sequence==29&&c.sequence==29,"overlap frame count");
            near(a.dominantHz,b.dominantHz,0,"partial reads");
            for(int i=0;i<a.bands.length;i++){near(a.bands[i],b.bands[i],0,"bands");near(a.bands[i],c.bands[i],0,"bands");}
        });
        test("dominant is strongest component, not assumed fundamental", () -> {
            short[] a=sine(48000,250,.1,16384),b=sine(48000,1000,.4,16384);
            for(int i=0;i<a.length;i++)a[i]+=b[i];SpectrumFrame f=analyze(48000,a,1024);
            near(f.dominantHz,1000,.16,"strongest component");
        });
        test("Hann suppresses distant leakage", () -> {
            SpectrumFrame f=analyze(48000,sine(48000,1000,.5,16384),2048);
            check(f.bands[0]<-75,"distant low-frequency leakage");check(f.bands[47]<-85,"distant high-frequency leakage");
        });
        test("peak hold decays and can be reset", () -> {
            SpectrumAnalyzer a=new SpectrumAnalyzer(48000);SpectrumFrame f=new SpectrumFrame();
            short[] loud=sine(48000,1000,.8,16384);a.accept(loud,loud.length,f::copyFrom);float peak=max(f.peaks);
            short[] quiet=new short[48000];a.accept(quiet,quiet.length,f::copyFrom);check(max(f.peaks)>peak-5,"hold too short");
            a.accept(quiet,quiet.length,f::copyFrom);check(max(f.peaks)<peak-8,"peak never decays");
            a.resetPeaks();a.accept(quiet,quiet.length,f::copyFrom);near(max(f.peaks),-120,.01,"reset");
        });
        test("waveform remains within PCM bounds and spans 12 ms", () -> {
            SpectrumFrame f=analyze(48000,sine(48000,1000,.5,16384),2048);
            near(f.waveMs,12,.01,"wave span");check(f.wave[0]>=-.03&&f.wave[0]<.08,"zero crossing trigger");
            for(float v:f.wave)check(Math.abs(v)<.501,"wave bound");
        });
        test("snapshot does not alias producer arrays", () -> {
            SpectrumFrame a=new SpectrumFrame(),b=new SpectrumFrame();a.bands[1]=-20;b.copyFrom(a);a.bands[1]=-60;near(b.bands[1],-20,0,"copy");
        });
        test("waterfall ring wrap and sample-rate reset", () -> {
            HudState h=new HudState();SpectrumFrame f=new SpectrumFrame();f.sampleRate=48000;
            for(int i=0;i<150;i++){f.bands[0]=-i;h.receive(f);}check(h.historyCount==100,"history count");
            near(h.history[(h.historyHead+99)%100][0],-149,0,"newest history row");
            f.sampleRate=16000;h.receive(f);check(h.historyCount==1,"rate change must reset time axis");
        });
        test("auto display scale cannot alter measured dBFS", () -> {
            HudState h=new HudState();SpectrumFrame f=new SpectrumFrame();f.sampleRate=48000;f.rmsDb=-63;
            Arrays.fill(f.bands,-60);for(int i=0;i<200;i++)h.receive(f);
            check(h.ceiling()>=-48.01&&h.ceiling()<=0,"scale bounds");near(h.frame.rmsDb,-63,0,"RMS changed");
            h.level=1;near(h.ceiling(),0,0,"fixed 0");h.level=2;near(h.ceiling(),-20,0,"fixed -20");h.level=3;near(h.ceiling(),-40,0,"fixed -40");
        });
        test("skipped UI frames leave gaps, not a stretched waterfall time axis", () -> {
            HudState h=new HudState();SpectrumFrame f=new SpectrumFrame();f.sampleRate=48000;f.sequence=1;f.bands[0]=-30;h.receive(f);
            f.sequence=5;h.receive(f);check(h.historyCount==5,"lost time was compressed");
            near(h.history[1][0],-120,0,"missing frame must be blank");near(h.history[4][0],-30,0,"newest row");
        });
        test("input recovery discards previous waterfall timing", () -> {
            HudState h=new HudState();SpectrumFrame f=new SpectrumFrame();f.sampleRate=48000;f.sequence=40;f.bands[0]=-25;h.receive(f);
            f.sequence=1;f.bands[0]=-45;h.receive(f);check(h.historyCount==1,"old stream history survived recovery");
            near(h.history[0][0],-45,0,"new stream row");
        });
        test("legacy CAM preference migrates to AUTO without selecting DEMO", () -> {
            check(HudState.restoreInput(5)==0,"CAM must become AUTO");
            for(int i=0;i<5;i++)check(HudState.restoreInput(i)==i,"existing input changed");
            check(HudState.restoreInput(-1)==0&&HudState.restoreInput(99)==0,"invalid preference");
        });
        test("invalid sample-rate and input counts fail explicitly", () -> {
            try {new SpectrumAnalyzer(0);throw new AssertionError("rate");}catch(IllegalArgumentException expected){}
            try {new SpectrumAnalyzer(48000).accept(new short[2],3,x->{});throw new AssertionError("count");}catch(IllegalArgumentException expected){}
        });
        System.out.println("RESULT " + passed + " tests passed");
    }
}
