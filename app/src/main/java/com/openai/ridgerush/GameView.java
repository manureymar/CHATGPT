package com.openai.ridgerush;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

public final class GameView extends View implements Runnable {
    private enum Screen { MENU, LEVELS, GAME, RESULT }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SharedPreferences prefs;
    private final String[] names = {"Valle Pino", "Dunas Rojas", "Cresta Helada", "Cantera Neón", "Cuenca Lunar"};
    private final int[] skyTop = {0xff63b3ed,0xffffb45e,0xff8cc9ff,0xff17213a,0xff0b1024};
    private final int[] skyBottom = {0xffd9f3ff,0xffffe3a8,0xffedf8ff,0xff8155d9,0xff3b245e};
    private final int[] ground = {0xff4d7a3b,0xffb36b3d,0xff96aabd,0xff34556a,0xff62657a};

    private volatile boolean running;
    private Thread loop;
    private long lastNs;
    private Screen screen = Screen.MENU;
    private int level = 0;
    private int unlocked;
    private int totalCoins;
    private float x, y, vx, vy, angle, omega, fuel, camera;
    private boolean gas, brake, finished;
    private int runCoins;
    private static final float END = 5000f;

    public GameView(Context context) {
        super(context);
        setFocusable(true);
        prefs = context.getSharedPreferences("ridge_progress", Context.MODE_PRIVATE);
        unlocked = Math.max(1, Math.min(5, prefs.getInt("unlocked", 1)));
        totalCoins = prefs.getInt("coins", 0);
    }

    public synchronized void resumeGame() {
        if (running) return;
        running = true;
        lastNs = System.nanoTime();
        loop = new Thread(this, "RidgeRushLoop");
        loop.start();
    }

    public synchronized void pauseGame() {
        running = false;
        gas = false;
        brake = false;
    }

    @Override public void run() {
        while (running) {
            long now = System.nanoTime();
            float dt = Math.min(0.033f, (now - lastNs) / 1_000_000_000f);
            lastNs = now;
            if (screen == Screen.GAME) update(dt);
            postInvalidateOnAnimation();
            try { Thread.sleep(10); } catch (InterruptedException ignored) { return; }
        }
    }

    private void startLevel(int index) {
        level = index;
        x = 160f;
        y = terrain(x) - 72f;
        vx = vy = angle = omega = camera = 0f;
        fuel = 100f;
        runCoins = 0;
        finished = false;
        gas = brake = false;
        screen = Screen.GAME;
    }

    private float terrain(float worldX) {
        float base = getHeight() > 0 ? getHeight() * 0.73f : 520f;
        switch (level) {
            case 0: return base + 35f*(float)Math.sin(worldX/150f) + 18f*(float)Math.sin(worldX/55f);
            case 1: return base + 55f*(float)Math.sin(worldX/230f) + 28f*(float)Math.sin(worldX/92f);
            case 2: return base + 45f*(float)Math.sin(worldX/175f) + 30f*(float)Math.sin(worldX/68f);
            case 3: return base + 35f*(float)Math.sin(worldX/105f) + 50f*(float)Math.sin(worldX/315f);
            default: return base + 68f*(float)Math.sin(worldX/235f) + 24f*(float)Math.sin(worldX/82f);
        }
    }

    private float slope(float worldX) {
        return (terrain(worldX + 4f) - terrain(worldX - 4f)) / 8f;
    }

    private void update(float dt) {
        float gravity = level == 4 ? 420f : 900f;
        float groundY = terrain(x);
        boolean grounded = y + 50f >= groundY;

        if (fuel > 0f) {
            if (gas) {
                if (grounded) vx += 430f * dt;
                else omega -= 1.8f * dt;
                fuel = Math.max(0f, fuel - 2.2f * dt);
            }
            if (brake) {
                if (grounded) vx -= 330f * dt;
                else omega += 1.8f * dt;
                fuel = Math.max(0f, fuel - 0.6f * dt);
            }
        }

        if (grounded) {
            float targetY = groundY - 50f;
            y += (targetY - y) * Math.min(1f, dt * 18f);
            vy = Math.min(vy, 0f);
            float targetAngle = (float)Math.atan(slope(x));
            omega += (targetAngle - angle) * 5.5f * dt;
            vx *= (float)Math.pow(gas || brake ? 0.998 : 0.985, dt * 60f);
        } else {
            vy += gravity * dt;
        }

        x += vx * dt;
        y += vy * dt;
        angle += omega * dt;
        omega *= (float)Math.pow(0.992, dt * 60f);
        vx = Math.max(-220f, Math.min(720f, vx));

        float nowGround = terrain(x);
        if (y + 50f > nowGround) {
            y = nowGround - 50f;
            if (vy > 0f) vy *= -0.10f;
        }

        int checkpoint = (int)(x / 350f);
        int expected = runCoins / 3 + 1;
        if (checkpoint >= expected && runCoins < 42) runCoins += 3;

        camera += ((x - getWidth() * 0.32f) - camera) * Math.min(1f, dt * 5f);
        camera = Math.max(0f, camera);

        if (Math.abs(angle) > 2.35f && Math.abs(vx) > 100f) {
            finish(false);
        } else if (x >= END) {
            finish(true);
        } else if (fuel <= 0f && Math.abs(vx) < 3f) {
            finish(false);
        }
    }

    private void finish(boolean win) {
        finished = win;
        totalCoins += runCoins + (win ? 75 + level * 25 : 0);
        if (win) unlocked = Math.min(5, Math.max(unlocked, level + 2));
        prefs.edit().putInt("coins", totalCoins).putInt("unlocked", unlocked).apply();
        gas = brake = false;
        screen = Screen.RESULT;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (screen == Screen.MENU) drawMenu(c);
        else if (screen == Screen.LEVELS) drawLevels(c);
        else {
            drawGame(c);
            if (screen == Screen.RESULT) drawResult(c);
        }
    }

    private void background(Canvas c, int top, int bottom) {
        p.setShader(new LinearGradient(0,0,0,getHeight(),top,bottom, Shader.TileMode.CLAMP));
        c.drawRect(0,0,getWidth(),getHeight(),p);
        p.setShader(null);
    }

    private void label(Canvas c, String s, float px, float py, float size, int color, Paint.Align align, boolean bold) {
        p.setColor(color);
        p.setTextSize(size);
        p.setTextAlign(align);
        p.setTypeface(Typeface.create("sans", bold ? Typeface.BOLD : Typeface.NORMAL));
        c.drawText(s, px, py, p);
    }

    private void button(Canvas c, float l, float t, float r, float b, String text, int color) {
        p.setColor(color);
        c.drawRoundRect(l,t,r,b,24,24,p);
        label(c,text,(l+r)/2f,(t+b)/2f+10f,28f,Color.WHITE,Paint.Align.CENTER,true);
    }

    private void drawMenu(Canvas c) {
        background(c,0xff14233b,0xff2c6a59);
        drawMountains(c);
        label(c,"RIDGE RUSH",getWidth()/2f,120,58,Color.WHITE,Paint.Align.CENTER,true);
        label(c,"TURBO TRAILS",getWidth()/2f,165,27,0xffffca45,Paint.Align.CENTER,true);
        drawCar(c,getWidth()/2f,285,0f,1.6f);
        float cx=getWidth()/2f;
        button(c,cx-190,360,cx+190,430,"JUGAR",0xffe74c3c);
        label(c,"5 niveles • conducción 2D • progreso local",cx,getHeight()-38,20,0xffe4edf7,Paint.Align.CENTER,false);
        label(c,"¢ "+totalCoins,getWidth()-35,55,24,0xffffca45,Paint.Align.RIGHT,true);
    }

    private void drawLevels(Canvas c) {
        background(c,0xff111827,0xff253b55);
        label(c,"SELECCIONA CIRCUITO",35,62,36,Color.WHITE,Paint.Align.LEFT,true);
        float margin=28f, gap=14f;
        float w=(getWidth()-margin*2-gap*4)/5f;
        float top=100f, bottom=getHeight()-85f;
        for(int i=0;i<5;i++) {
            float l=margin+i*(w+gap), r=l+w;
            p.setColor(i<unlocked?0xff263c54:0xff252b35);
            c.drawRoundRect(l,top,r,bottom,22,22,p);
            p.setColor(skyTop[i]); c.drawRoundRect(l+8,top+8,r-8,top+130,16,16,p);
            p.setColor(ground[i]);
            Path hill=new Path(); hill.moveTo(l+8,top+95);
            for(int j=0;j<=8;j++) hill.lineTo(l+8+(w-16)*j/8f,top+95+18f*(float)Math.sin(j*.9+i));
            hill.lineTo(r-8,top+130); hill.lineTo(l+8,top+130); hill.close(); c.drawPath(hill,p);
            label(c,names[i],(l+r)/2f,top+174,18,Color.WHITE,Paint.Align.CENTER,true);
            label(c,i<unlocked?"LISTO":"BLOQUEADO",(l+r)/2f,top+207,15,i<unlocked?0xff70e09b:0xff9aa4b2,Paint.Align.CENTER,true);
        }
        button(c,28,getHeight()-70,175,getHeight()-20,"ATRÁS",0xff465163);
    }

    private void drawGame(Canvas c) {
        background(c,skyTop[level],skyBottom[level]);
        drawMountains(c);
        c.save();
        c.translate(-camera,0);
        Path terrainPath=new Path();
        float from=Math.max(0,camera-80), to=camera+getWidth()+120;
        terrainPath.moveTo(from,getHeight()+100);
        terrainPath.lineTo(from,terrain(from));
        for(float wx=from;wx<=to;wx+=12f) terrainPath.lineTo(wx,terrain(wx));
        terrainPath.lineTo(to,getHeight()+100); terrainPath.close();
        p.setColor(ground[level]); c.drawPath(terrainPath,p);
        drawFinish(c);
        drawCar(c,x,y,angle,1f);
        c.restore();

        p.setColor(0xaa10131b); c.drawRoundRect(15,15,270,74,18,18,p);
        label(c,names[level],30,42,18,Color.WHITE,Paint.Align.LEFT,true);
        label(c,(int)x+" m",30,65,16,0xffdce6f3,Paint.Align.LEFT,false);
        label(c,"¢ "+(totalCoins+runCoins),getWidth()-25,48,21,0xffffca45,Paint.Align.RIGHT,true);
        label(c,"COMBUSTIBLE",295,43,14,Color.WHITE,Paint.Align.LEFT,true);
        p.setColor(0xff26313f); c.drawRoundRect(395,28,510,51,8,8,p);
        p.setColor(fuel<20?0xffef5350:0xff48c774); c.drawRoundRect(395,28,395+115*Math.max(0,fuel)/100f,51,8,8,p);

        float by=getHeight()-90;
        button(c,18,by,155,getHeight()-18,"FRENO",brake?0xfff0a43b:0xaa344154);
        button(c,getWidth()-155,by,getWidth()-18,getHeight()-18,"GAS",gas?0xff3cc46e:0xaa344154);
    }

    private void drawMountains(Canvas c) {
        p.setColor(0x334c6278);
        Path m=new Path(); m.moveTo(0,getHeight()*.60f);
        for(int px=0;px<=getWidth();px+=110) m.lineTo(px,getHeight()*.44f+50f*(float)Math.sin((px+level*70)/145f));
        m.lineTo(getWidth(),getHeight()); m.lineTo(0,getHeight()); m.close(); c.drawPath(m,p);
    }

    private void drawFinish(Canvas c) {
        float fy=terrain(END);
        p.setColor(Color.WHITE); c.drawRect(END,fy-175,END+7,fy,p);
        for(int row=0;row<4;row++) for(int col=0;col<4;col++) {
            p.setColor(((row+col)&1)==0?Color.WHITE:Color.BLACK);
            c.drawRect(END+7+col*16,fy-170+row*16,END+23+col*16,fy-154+row*16,p);
        }
    }

    private void drawCar(Canvas c,float cx,float cy,float a,float scale) {
        c.save(); c.translate(cx,cy); c.rotate((float)Math.toDegrees(a)); c.scale(scale,scale);
        p.setColor(0xffe74c3c);
        Path body=new Path(); body.moveTo(-50,3); body.lineTo(-35,-20); body.lineTo(12,-24); body.lineTo(39,-10); body.lineTo(50,12); body.lineTo(44,24); body.lineTo(-45,24); body.close(); c.drawPath(body,p);
        p.setColor(0xffd8ecff); Path win=new Path(); win.moveTo(-14,-21); win.lineTo(2,-39); win.lineTo(20,-20); win.close(); c.drawPath(win,p);
        p.setColor(0xff20252c); c.drawCircle(-33,27,22,p); c.drawCircle(34,27,22,p);
        p.setColor(0xffaab5c4); c.drawCircle(-33,27,8,p); c.drawCircle(34,27,8,p);
        p.setColor(0xfff1c27d); c.drawCircle(2,-37,9,p);
        c.restore();
    }

    private void drawResult(Canvas c) {
        p.setColor(0xb8000000); c.drawRect(0,0,getWidth(),getHeight(),p);
        float cx=getWidth()/2f, cy=getHeight()/2f;
        p.setColor(0xff1e2938); c.drawRoundRect(cx-260,cy-170,cx+260,cy+175,28,28,p);
        label(c,finished?"¡META!":"CARRERA TERMINADA",cx,cy-95,39,finished?0xff6ee7a2:0xffff7b7b,Paint.Align.CENTER,true);
        label(c,finished?"Circuito completado":"Inténtalo de nuevo",cx,cy-52,21,0xffd9e5f4,Paint.Align.CENTER,false);
        label(c,"+"+(runCoins+(finished?75+level*25:0))+" monedas",cx,cy-5,22,0xffffca45,Paint.Align.CENTER,true);
        button(c,cx-220,cy+48,cx-15,cy+118,"REPETIR",0xffe74c3c);
        button(c,cx+15,cy+48,cx+220,cy+118,"NIVELES",0xff3066be);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action=e.getActionMasked();
        float tx=e.getX(), ty=e.getY();
        if(screen==Screen.GAME) {
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_MOVE) {
                gas=hasPointer(e,true,-1); brake=hasPointer(e,false,-1);
            } else if(action==MotionEvent.ACTION_POINTER_UP) {
                int skip=e.getActionIndex(); gas=hasPointer(e,true,skip); brake=hasPointer(e,false,skip);
            } else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) { gas=brake=false; }
            return true;
        }
        if(action!=MotionEvent.ACTION_UP) return true;
        if(screen==Screen.MENU) {
            if(ty>330 && ty<455) screen=Screen.LEVELS;
        } else if(screen==Screen.LEVELS) {
            if(ty>getHeight()-90 && tx<200) screen=Screen.MENU;
            else {
                float margin=28f,gap=14f,w=(getWidth()-margin*2-gap*4)/5f;
                int idx=(int)((tx-margin)/(w+gap));
                if(idx>=0 && idx<unlocked && idx<5 && ty>90 && ty<getHeight()-85) startLevel(idx);
            }
        } else if(screen==Screen.RESULT) {
            float cx=getWidth()/2f, cy=getHeight()/2f;
            if(ty>cy+35 && ty<cy+140) { if(tx<cx) startLevel(level); else screen=Screen.LEVELS; }
        }
        invalidate();
        return true;
    }

    private boolean hasPointer(MotionEvent e, boolean right, int skip) {
        for(int i=0;i<e.getPointerCount();i++) if(i!=skip) {
            float px=e.getX(i), py=e.getY(i);
            if(py>getHeight()-135 && (right ? px>getWidth()-200 : px<200)) return true;
        }
        return false;
    }
}
