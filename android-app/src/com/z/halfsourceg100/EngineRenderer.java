package com.z.halfsourceg100;

import android.opengl.GLSurfaceView;
import android.util.Log;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Frame sink for Phase 0. The engine's togles backend drives most of the
 * frame; this renderer guarantees the surface is created at native resolution
 * and forwards draw calls to the engine each frame once it's wired in.
 */
public class EngineRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "EngineRenderer";
    private final String filesDir;

    public EngineRenderer(String filesDir) {
        this.filesDir = filesDir;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.i(TAG, "surface created (native resolution, GLES3)");
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // width/height ARE the panel size here — we never request a smaller
        // surface, and the engine must not render to a scaled target.
        Log.i(TAG, "surface " + width + "x" + height);
        // glViewport(0, 0, width, height) is the engine's job via togles;
        // no scaling code is allowed on this path.
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Phase 0: engine loop not yet wired; clear to black as a heartbeat.
        gl.glClearColor(0f, 0f, 0f, 1f);
        gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
    }
}
