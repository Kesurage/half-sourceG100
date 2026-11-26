package com.z.halfsourceg100;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;

/**
 * Engine surface (Phase 0 shell, now a dedicated activity).
 *
 * Loads the nillerusr-lineage engine .so set (built by
 * scripts/build-engine-aarch64.sh) and hands it a GLES 3.0 context at the
 * panel's NATIVE resolution. No scaling, no render targets below display
 * size. When the engine bundle is not shipped (launcher-only builds) this
 * activity explains the state instead of crashing.
 */
public class EngineActivity extends Activity {
    private static final String TAG = "half-sourceG100";

    private GLSurfaceView view;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String game = getIntent() != null ? getIntent().getStringExtra("game") : "hl2";
        if (game == null || game.isEmpty()) game = "hl2";

        if (!EngineJni.available()) {
            TextView tv = new TextView(this);
            tv.setPadding(32, 32, 32, 32);
            tv.setText("Engine bundle not included in this build.\n\n"
                    + "Game that would launch:  -game " + game + "\n\n"
                    + "The engine .so set ships as its own release once the "
                    + "Phase-0 engine build lands. This APK is the mod manager "
                    + "(catalog, classifier, Tier-2 import).");
            setContentView(tv);
            return;
        }

        // immersive, keep screen on — we're a game
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        String filesDir = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null).getAbsolutePath()
                : getFilesDir().getAbsolutePath();
        Log.i(TAG, "content root: " + filesDir + "  game: " + game);

        view = new GLSurfaceView(this);
        // GLES 3.0 context — the togles renderer's minimum
        view.setEGLContextClientVersion(3);
        view.setEGLConfigChooser(new GLSurfaceView.EGLConfigChooser() {
            public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
                // 8888 + depth 24 + stencil 8, at native surface size
                int[] attrs = {
                        EGL10.EGL_RED_SIZE, 8,
                        EGL10.EGL_GREEN_SIZE, 8,
                        EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 24,
                        EGL10.EGL_STENCIL_SIZE, 8,
                        EGL10.EGL_RENDERABLE_TYPE, 4 /* EGL_OPENGL_ES2_BIT */,
                        EGL10.EGL_NONE
                };
                EGLConfig[] out = new EGLConfig[1];
                int[] num = new int[1];
                if (!egl.eglChooseConfig(display, attrs, out, 1, num) || num[0] == 0) {
                    throw new RuntimeException("no suitable EGLConfig");
                }
                return out[0];
            }
        });

        view.setRenderer(new EngineRenderer(filesDir));
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        if (view != null) view.queueEvent(EngineJni::onPause);
        super.onPause();
        if (view != null) view.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) view.onResume();
        if (view != null) view.queueEvent(EngineJni::onResume);
    }

    @Override
    protected void onDestroy() {
        if (view != null) view.queueEvent(EngineJni::onShutdown);
        super.onDestroy();
    }
}
