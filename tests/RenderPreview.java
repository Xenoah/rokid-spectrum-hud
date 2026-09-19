import dev.xenoah.spectrum.core.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/** Runs the APK's actual platform-independent renderer against Java2D. Not an emulator screenshot. */
public final class RenderPreview implements HudRenderer.Surface {
    final BufferedImage image=new BufferedImage(480,400,BufferedImage.TYPE_INT_RGB);
    final Graphics2D g=image.createGraphics();
    static int checked;
    RenderPreview(){g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);}
    void color(int value){g.setColor(new Color(0,Math.max(0,Math.min(255,value)),0));}
    public void rect(float x,float y,float w,float h,int v){color(v);g.fill(new Rectangle2D.Float(x,y,w,h));}
    public void line(float a,float b,float c,float d,float w,int v){color(v);g.setStroke(new BasicStroke(w));g.draw(new Line2D.Float(a,b,c,d));}
    public void text(String text,float x,float y,float size,int v,boolean bold){
        color(v);g.setFont(new Font("DejaVu Sans",bold?Font.BOLD:Font.PLAIN,Math.round(size)));
        float width=(float)g.getFontMetrics().getStringBounds(text,g).getWidth();
        if(x<0||x+width>479.5||y>399||y-g.getFontMetrics().getAscent()<0)throw new AssertionError("Text out of HUD bounds: "+text+" right="+(x+width));
        g.drawString(text,x,y);checked++;
    }
    void save(File file)throws Exception{ImageIO.write(image,"png",file);g.dispose();}
    static void render(HudState h,File folder,String name)throws Exception{RenderPreview s=new RenderPreview();new HudRenderer().draw(s,h);s.save(new File(folder,name+".png"));}
    public static void main(String[] args)throws Exception{
        File folder=new File(args[0]);folder.mkdirs();HudState h=new HudState();h.status="DEMO";h.source="DEMO / GENERATED";
        SpectrumAnalyzer analyzer=new SpectrumAnalyzer(48000);Random r=new Random(5);short[] data=new short[2048];long sample=0;
        for(int frame=0;frame<150;frame++){
            for(int i=0;i<data.length;i++,sample++){
                double t=sample/48000.0;
                double v=.22*Math.sin(2*Math.PI*1000*t)+.07*Math.sin(2*Math.PI*250*t)+.035*Math.sin(2*Math.PI*4000*t)+.003*(r.nextDouble()*2-1);
                data[i]=(short)Math.round(32767*v);
            }
            analyzer.accept(data,data.length,h::receive);
        }
        for(int mode=0;mode<3;mode++){h.mode=mode;render(h,folder,HudState.MODES[mode].toLowerCase());}
        h.mode=0;h.menu=true;h.menuIndex=3;render(h,folder,"menu");h.help=true;render(h,folder,"help");h.help=false;h.menu=false;
        for(String status:new String[]{"STARTING","PERMISSION","MIC ERROR","NO SIGNAL","WAITING","MIC MUTED","SCANNING","MIC BLOCKED"}){
            h.status=status;h.diagnostic="MIC / 48000 Hz / NONPRIVATE";
            h.inputSummary=status.equals("SCANNING")?"API 34 / MODE 0 / OS ROUTE":"TESTED 9 / BLOCKED 9 / ZERO 0 / ERR 0";
            h.detail=status.equals("WAITING")?"System policy is silencing this input.":status.equals("MIC MUTED")?"Microphone is muted in system settings.":status.equals("MIC BLOCKED")?"System policy silenced the tested inputs.":status.equals("SCANNING")?"Input 9 / 9 silenced; trying shared routes.":"Digital silence. Check mic privacy / input.";
            render(h,folder,status.toLowerCase().replace(' ','-'));
        }
        h.status="WAITING";h.detail="Assistant / another window is active.";
        h.diagnostic="Microphone released. Return to resume.";render(h,folder,"assistant-overlay");
        h.status="LIVE";h.source="VOICE / NONPRIVATE";render(h,folder,"source-label");
        h.frozen=true;render(h,folder,"hold");h.frozen=false;
        h.status="WAITING";h.haveFrame=false;h.frozen=true;render(h,folder,"hold-before-input");h.frozen=false;
        // Preview sheet: same renderer, synthetic data, no physical-device claim.
        BufferedImage sheet=new BufferedImage(1440,452,BufferedImage.TYPE_INT_RGB);Graphics2D sg=sheet.createGraphics();
        sg.setColor(new Color(14,19,15));sg.fillRect(0,0,1440,452);
        sg.setFont(new Font("DejaVu Sans",Font.PLAIN,16));sg.setColor(new Color(180,220,187));
        sg.drawString("ROKID SPECTRUM  /  Shared-code preview  /  Synthetic test input",20,29);
        for(int i=0;i<3;i++)sg.drawImage(ImageIO.read(new File(folder,HudState.MODES[i].toLowerCase()+".png")),i*480,52,null);
        ImageIO.write(sheet,"png",new File(folder,"RokidSpectrum-preview.png"));sg.dispose();
        BufferedImage portrait=new BufferedImage(480,640,BufferedImage.TYPE_INT_RGB);Graphics2D pg=portrait.createGraphics();
        pg.drawImage(ImageIO.read(new File(folder,"spectrum.png")),0,120,null);pg.dispose();ImageIO.write(portrait,"png",new File(folder,"safe-area-480x640.png"));
        System.out.println("PASS shared-code rendering / 17 UI states / "+checked+" text bounds checks / 480x400 and 480x640");
    }
}
