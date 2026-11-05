package org.gboardopenai.test;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import org.gboardopenai.VoicePanel;
import org.gboardopenai.VoiceUiState;
import org.gboardopenai.VoiceWindowInsets;

/** Deterministic rendering and touch tests; no microphone or network request. */
public final class VoicePanelChecks {
    private static final int WIDTH=480, HEIGHT=900, CENTER_Y=HEIGHT/2;
    private static final int SCRIM=0xCC000000;
    private static final class Panel extends VoicePanel {
        long clock=1000;
        Panel(Context context){super(context,SCRIM,Color.CYAN);}
        @Override protected long frameTime(){return clock;}
    }
    public static void run(Context context) {
        Panel panel=makePanel(context);
        int[] taps={0};VoiceUiState state=new VoiceUiState();state.begin();state.recording();
        panel.setOnClickListener(v->{taps[0]++;state.stop();panel.setPhase(state.phase());});
        for(int[] point:new int[][]{{1,1},{WIDTH-2,1},{1,HEIGHT-2},{WIDTH-2,HEIGHT-2},{WIDTH/2,HEIGHT/2}}){
            MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,point[0],point[1],0);
            MotionEvent up=MotionEvent.obtain(0,30,MotionEvent.ACTION_UP,point[0],point[1],0);
            if(!panel.dispatchTouchEvent(down) || !panel.dispatchTouchEvent(up))throw new AssertionError("Uncovered full-screen touch");
            down.recycle();up.recycle();
            // Detached views queue framework clicks until attachment; also exercise the accessible action.
            panel.performClick();
        }
        if(taps[0]<5 || state.phase()!=VoiceUiState.Phase.PROCESSING)throw new AssertionError("Tap coverage or duplicate-stop failure");
        AccessibilityNodeInfo info=AccessibilityNodeInfo.obtain();panel.onInitializeAccessibilityNodeInfo(info);
        if(info.isClickable() || !panel.getContentDescription().toString().contains("Processing"))throw new AssertionError("Processing accessibility");
        info.recycle();
        Bitmap bitmap=render(panel);
        if(bitmap.getPixel(1,1)!=SCRIM)throw new AssertionError("Panel must retain the translucent recording scrim");
        bitmap.recycle();panel.clear();
        if(panel.getKeepScreenOn())throw new AssertionError("Keep-screen-on leaked");
        checkSpeechContrast(context);checkProcessingHasNoSignal(context);checkScrimComposition(context);checkInsets();
        for(int density:new int[]{160,320,480}){
            Configuration config=new Configuration(context.getResources().getConfiguration());config.densityDpi=density;
            checkProcessingHasNoSignal(context.createConfigurationContext(config));
            for(int[] size:new int[][]{{480,900},{900,480},{481,901}})
                checkVerticalCenter(context.createConfigurationContext(config),size[0],size[1]);
        }
        org.gboardopenai.VoiceWindowChecks.run(context);
    }
    private static void checkVerticalCenter(Context context,int width,int height){
        Panel panel=new Panel(context);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        panel.layout(0,0,width,height);panel.setPhase(VoiceUiState.Phase.LISTENING);
        for(int frame=0;frame<4;frame++){
            if(frame==1)panel.level(.45f);
            panel.clock+=frame>=2?600:16;
            Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);panel.draw(new Canvas(bitmap));
            int top=height,bottom=-1;
            for(int y=0;y<height;y++)if(Color.green(bitmap.getPixel(width/2,y))>20){top=Math.min(top,y);bottom=y;}
            if(bottom<0 || Math.abs((top+bottom+1)/2f-height/2f)>1)
                throw new AssertionError("Voice animation is not vertically centered, frame="+frame+", size="+width+"x"+height);
            bitmap.recycle();
        }
        panel.clear();
    }
    private static Panel makePanel(Context context){
        Panel panel=new Panel(context);
        panel.measure(View.MeasureSpec.makeMeasureSpec(WIDTH,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(HEIGHT,View.MeasureSpec.EXACTLY));
        panel.layout(0,0,WIDTH,HEIGHT);panel.setPhase(VoiceUiState.Phase.LISTENING);return panel;
    }
    private static void checkSpeechContrast(Context context){
        Panel quiet=makePanel(context);quiet.level(.1f);Bitmap silent=render(quiet);
        Panel speaking=makePanel(context);speaking.level(.45f);Bitmap active=render(speaking);
        if(signalHeight(active)<signalHeight(silent)*10)throw new AssertionError("Speech needs exaggerated height contrast");
        if(Color.green(active.getPixel(WIDTH/2,CENTER_Y))<=Color.green(silent.getPixel(WIDTH/2,CENTER_Y))+20)
            throw new AssertionError("Speech needs brightness contrast");
        speaking.clock+=2000;Bitmap paused=render(speaking);paused.recycle();
        speaking.clock+=2000;paused=render(speaking);
        if(signalHeight(paused)>=signalHeight(active))throw new AssertionError("Waveform must settle during silence");
        if(!speaking.getKeepScreenOn())throw new AssertionError("Silence must not finish recording");
        silent.recycle();active.recycle();paused.recycle();quiet.clear();speaking.clear();
    }
    private static void checkProcessingHasNoSignal(Context context){
        Panel panel=makePanel(context);panel.level(.45f);Bitmap spoken=render(panel);spoken.recycle();
        panel.setPhase(VoiceUiState.Phase.PROCESSING);panel.clock+=500;
        Bitmap first=render(panel);
        if(signalHeight(first)!=0)throw new AssertionError("Processing must not leave a full-screen signal or shape");
        if(panel.getKeepScreenOn())throw new AssertionError("Processing must release keep-screen-on");
        panel.clock+=800;Bitmap next=render(panel);
        if(!first.sameAs(next))throw new AssertionError("Detached processing panel must not animate a shape");
        next.recycle();
        first.recycle();panel.clear();
    }
    private static void checkScrimComposition(Context context){
        Panel panel=makePanel(context);
        Bitmap image=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(image);
        canvas.drawColor(Color.WHITE);
        android.graphics.Paint paint=new android.graphics.Paint();paint.setColor(Color.RED);
        canvas.drawRect(0,0,10,10,paint);
        panel.draw(canvas);
        int white=image.getPixel(20,5),red=image.getPixel(5,5);
        int expected=255-Color.alpha(SCRIM);
        if(Math.abs(Color.red(white)-expected)>1 || Color.red(white)!=Color.green(white)
                || Color.red(red)!=Color.red(white) || Color.green(red)!=0 || Color.blue(red)!=0)
            throw new AssertionError("Scrim must dim visible content uniformly without blur");
        if(image.getPixel(9,5)!=red || image.getPixel(10,5)!=white)
            throw new AssertionError("Scrim blurred the underlying edge");
        image.recycle();panel.clear();
    }
    private static void checkInsets(){
        InputMethodService.Insets insets=new InputMethodService.Insets();
        insets.contentTopInsets=600;insets.visibleTopInsets=610;
        insets.touchableInsets=InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE;
        VoiceWindowInsets.apply(insets,null);
        VoiceWindowInsets.apply(insets,new Rect());
        if(insets.touchableInsets!=InputMethodService.Insets.TOUCHABLE_INSETS_VISIBLE)throw new AssertionError("Inactive touch bounds changed");
        VoiceWindowInsets.apply(insets,new Rect(0,0,WIDTH,HEIGHT));
        if(insets.contentTopInsets!=600 || insets.visibleTopInsets!=610)throw new AssertionError("Editor layout was changed");
        if(insets.touchableInsets!=InputMethodService.Insets.TOUCHABLE_INSETS_REGION || !insets.touchableRegion.contains(1,1) || !insets.touchableRegion.contains(WIDTH-1,HEIGHT-1))
            throw new AssertionError("Full-screen touches must reach the IME");
    }
    private static Bitmap render(VoicePanel panel){Bitmap image=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);panel.draw(new Canvas(image));return image;}
    private static int signalHeight(Bitmap image){
        int top=HEIGHT,bottom=-1;for(int y=0;y<HEIGHT;y++)if(Color.green(image.getPixel(WIDTH/2,y))>20){top=Math.min(top,y);bottom=y;}
        return bottom<0?0:bottom-top+1;
    }
}
