package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Caches the static part of the Build 42 world map in an offscreen texture.
 *
 * <p>Every frame the map re-sends all of its geometry: pan and zoom are baked into the vertex
 * positions on the CPU, so nothing survives from one frame to the next. On an Exynos 990 that costs
 * a constant 275 ms per frame with the CPU idle, on NG_GL4ES and MobileGlues alike (tester reports
 * 2026-11-22..24), i.e. the GPU or its driver is the limit, not our upload path.</p>
 *
 * <p>{@code WorldMapRenderer$Drawer.renderInternal()} draws, in order: terrain, features, street
 * labels, built-in place names, zone overlays and the fog of war (the "base"), then
 * {@code renderPlayers()} and a short live tail (outside-bounds paint, user annotations). Nothing in
 * the base depends on time. So while the view and the content stay the same, the base is drawn
 * once into a screen-sized framebuffer (RECORD) and later frames only copy that picture back and run
 * the live tail (REPLAY). Any change of view, size, style, options, symbols, street labels or fog
 * falls back to the normal path (LIVE) until the view holds still for two frames.</p>
 *
 * <p>A frame drawn while map data is still loading is not kept: the game triangulates at most 500
 * polygons per frame and image tiles stream in, so a record that triangulated anything stays
 * invalid, and a valid picture is re-recorded 0.3, 0.7, 1.5 and then every 3 seconds as a backstop.</p>
 *
 * <p>Opt-in for now: {@code ZOMDROID_MAPFBO=1} (Env vars) or {@code -Dzomdroid.mapFbo=true}. While
 * off nothing is patched. A {@code [map-fbo]} line every 10 s of open map reports the frame split.
 * Any failure turns the cache off for the session and the map renders as before.</p>
 *
 * <p>Everything game-facing is resolved by reflection once, on the render thread, the first time a
 * map draws; the agent has no compile dependency on the game or LWJGL.</p>
 */
public final class MapFbo {
    public static final boolean ENABLED = readSwitch();

    private static final int LIVE = 0, RECORD = 1, REPLAY = 2, GATED = 3;
    private static final java.util.Set<String> loggedGates = new java.util.HashSet<>();
    private static final long[] REFRESH_NS = {300_000_000L, 700_000_000L, 1_500_000_000L, 3_000_000_000L};
    private static final long IDLE_FREE_NS = 5_000_000_000L;
    private static final long STATS_NS = 10_000_000_000L;

    // GL constants
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6, GL_SCISSOR_TEST = 0x0C11,
            GL_COLOR_CLEAR_VALUE = 0x0C22, GL_COLOR_BUFFER_BIT = 0x4000, GL_BLEND = 0x0BE2,
            GL_PROJECTION = 5889, GL_MODELVIEW = 5888;

    private MapFbo() {
    }

    private static boolean readSwitch() {
        String v = System.getenv("ZOMDROID_MAPFBO");
        if (v == null || v.isEmpty()) v = System.getProperty("zomdroid.mapFbo", "");
        v = v.trim();
        return v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("on");
    }

    // -------------------- state (render thread only) --------------------

    private static boolean resolved, off, recording, valid, loggedBinding;
    private static Object fbo, tex, texId, funcs, orthoMatrix, identityMatrix;
    private static int fboW, fboH, texWHW, texHHW, glFramebuffer, prevRawFbo, refreshIndex;
    private static long lastUse, recordStart, refreshAt, statsAt;

    private static float[] cur = new float[128], last = new float[128], rec = new float[128], ok = new float[128];
    private static int curN, lastN = -1, recN, okN = -1;
    private static Object[] curR = new Object[64], lastR = new Object[64], recR = new Object[64], okR = new Object[64];
    private static int curRN, lastRN = -1, recRN, okRN = -1;
    private static final float[] matrix16 = new float[16], clearSaved = new float[4];

    // stats since the last [map-fbo] line
    private static int sLive, sGated, sRecord, sReplay, sIncomplete;
    private static long sRecordNs, sReplayNs;

    // -------------------- resolved members --------------------

    private static Field fX, fY, fW, fH, fCenterX, fCenterY, fZoomF, fWorldScale, fModelView, fFill,
            fStyle, fRenderer, fWorldMap, fTriangulations, fSymbolsRenderData, fStreetRenderData;
    private static Field fRgbaR, fRgbaG, fRgbaB, fRgbaA, fStyleLayers, fOptions, fVisited,
            fVisitedChanged, fSymList, fStreetChars, fStreetLines, fStreetPolygon, fStreetTriangles,
            fCoreW, fCoreH, fRestoreTextures, fRestoreVbos, fRingBuffer, fVboLines, fFormatPCU;
    private static Field[] gateOptions;
    private static Field fWorldBounds;
    private static MethodHandle mRenderWorldBounds;
    private static MethodHandle mBoolValue, mDoubleValue, mMatrixGet, mTroveSize, mGetBoolean,
            mMinX, mMinY, mMaxX, mMaxY, mRenderPlayers, mPaintOutside, mSymbolsRender, mVboFlush,
            mVboGetInstance, mStartRun, mSetTextureId, mSetMode, mAddQuad, mEndRun, mPushLoad,
            mPopMatrix, mGlStateRestore, mForgetShader, mNewTexture, mNewFbo, mGetBufferId,
            mGetTextureId, mWidthHW, mHeightHW, mStartDrawing, mEndDrawing, mCurrentId, mFboDestroy,
            mBindFramebuffer, mCheckStatus, mGlFramebuffer, mGlComplete, mSetOrtho2D, mIdentity;
    private static MethodHandle glViewport, glGetInteger, glIsEnabled, glEnable, glDisable,
            glClearColor, glClear, glGetFloatv, glTexEnvi, glPolygonMode, glDepthMask,
            glActiveTexture, glUseProgram;
    private static Class<?> boolOptionClass, doubleOptionClass;

    // -------------------- advice --------------------

    public static class renderInternal {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object drawer) {
            return MapFbo.enter(drawer);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object drawer) {
            MapFbo.exit();
        }
    }

    public static class renderPlayers {
        @Advice.OnMethodEnter
        public static void enter(@Advice.This Object drawer) {
            MapFbo.beforePlayers(drawer);
        }
    }

    /** SpriteRenderer.acquireStateForRendering, render thread: frees the picture once no map draws. */
    public static class tick {
        @Advice.OnMethodExit
        public static void exit() {
            MapFbo.idle();
        }
    }

    // -------------------- per frame (public: the advice is inlined into game classes) --------------------

    public static boolean enter(Object d) {
        if (off) return false;
        try {
            if (!resolved) {
                resolve(d.getClass());
                if (off) return false;
            }
            long now = System.nanoTime();
            lastUse = now;
            stats(now);
            switch (decide(d, now)) {
                case REPLAY:
                    replay(d);
                    sReplay++;
                    sReplayNs += System.nanoTime() - now;
                    return true;
                case RECORD:
                    if (startRecord(d, now)) {
                        sRecord++;
                        return false;
                    }
                    // fall through: no framebuffer yet
                case LIVE:
                    sLive++;
                    return false;
                default: // GATED, counted in decide
                    return false;
            }
        } catch (Throwable t) {
            fail("enter", t);
            return false;
        }
    }

    public static void exit() {
        if (!recording) return;
        // renderInternal threw before renderPlayers: give the screen back, keep nothing.
        recording = false;
        valid = false;
        try {
            endRecordBinding();
        } catch (Throwable ignored) {
        }
    }

    public static void beforePlayers(Object d) {
        if (!recording) return;
        recording = false;
        try {
            mVboFlush.invoke(fVboLines.get(null));
            endRecordBinding();
            boolean complete = fTriangulations.getInt(d) == 0;
            composite(d);
            mapState(); // what renderInternal set before the fog pass, for renderPlayers and the tail
            long now = System.nanoTime();
            sRecordNs += now - recordStart;
            if (complete) {
                // A refresh of the same view backs off; a new view starts the schedule over.
                boolean sameAsBefore = okN >= 0 && keyEquals(rec, recN, recR, recRN, ok, okN, okR, okRN);
                ok = copyInto(ok, rec, recN);
                okR = copyInto(okR, recR, recRN);
                okN = recN;
                okRN = recRN;
                valid = true;
                refreshIndex = sameAsBefore ? Math.min(refreshIndex + 1, REFRESH_NS.length - 1) : 0;
                refreshAt = now + REFRESH_NS[refreshIndex];
            } else {
                valid = false;
                sIncomplete++;
            }
        } catch (Throwable t) {
            fail("record", t);
        }
    }

    public static void idle() {
        if (fbo == null) return;
        if (System.nanoTime() - lastUse < IDLE_FREE_NS) return;
        destroyFbo();
        valid = false;
    }

    // -------------------- decision --------------------

    private static int decide(Object d, long now) throws Throwable {
        String gate = buildKey(d);
        if (gate != null) {
            sGated++;
            // No reset of the previous key here: the minimap is gated on every frame in between
            // two frames of the full map, and the key already holds the renderer identity.
            // Once per reason, so a gate that fires on every frame of a normal map is visible.
            if (loggedGates.add(gate)) System.out.println("[map-fbo] live, not cached: " + gate);
            return GATED;
        }
        // "same": the view held still since the previous map frame.
        boolean same = lastN >= 0 && keyEquals(cur, curN, curR, curRN, last, lastN, lastR, lastRN);
        if (!same) {
            last = copyInto(last, cur, curN);
            lastR = copyInto(lastR, curR, curRN);
            lastN = curN;
            lastRN = curRN;
        }

        // The fog texture changed on the main thread; only a drawn frame uploads it.
        Object visited = fVisited.get(fRenderer.get(d));
        if (visited != null && fVisitedChanged.getBoolean(visited)) valid = false;

        if (valid && now < refreshAt && keyEquals(cur, curN, curR, curRN, ok, okN, okR, okRN)) return REPLAY;
        if (same) {
            rec = copyInto(rec, cur, curN);
            recR = copyInto(recR, curR, curRN);
            recN = curN;
            recRN = curRN;
            return RECORD;
        }
        return LIVE;
    }

    private static float[] copyInto(float[] dst, float[] src, int n) {
        if (dst.length < n) dst = new float[src.length];
        System.arraycopy(src, 0, dst, 0, n);
        return dst;
    }

    private static Object[] copyInto(Object[] dst, Object[] src, int n) {
        if (dst.length < n) dst = new Object[src.length];
        System.arraycopy(src, 0, dst, 0, n);
        Arrays.fill(dst, n, dst.length, null); // no stale references to old layers or maps
        return dst;
    }

    /** Fills cur/curR; returns why this map must render live, or null when it may be cached. */
    private static String buildKey(Object d) throws Throwable {
        Object renderer = fRenderer.get(d);
        for (Field g : gateOptions) if ((boolean) mBoolValue.invoke(g.get(renderer))) return "option " + g.getName();
        Object fill = fFill.get(d);
        float fa = fill == null ? 0f : fRgbaA.getFloat(fill);
        if (fa < 0.999f) return "background alpha " + fa;
        int w = fW.getInt(d), h = fH.getInt(d), sw = fCoreW.getInt(null), sh = fCoreH.getInt(null);
        // Full map only: the minimap re-centres on the player every frame and gains little.
        if ((long) w * h * 2 < (long) sw * sh) return "small map (minimap)";

        curN = 0;
        curRN = 0;
        put(fX.getInt(d)); put(fY.getInt(d)); put(w); put(h); put(sw); put(sh);
        put(fCenterX.getFloat(d)); put(fCenterY.getFloat(d)); put(fZoomF.getFloat(d)); put(fWorldScale.getFloat(d));
        mMatrixGet.invoke(fModelView.get(d), matrix16);
        for (float f : matrix16) put(f);
        put(fRgbaR.getFloat(fill)); put(fRgbaG.getFloat(fill)); put(fRgbaB.getFloat(fill)); put(fa);

        List<?> options = (List<?>) fOptions.get(renderer);
        for (int i = 0, n = options.size(); i < n; i++) {
            Object o = options.get(i);
            if (boolOptionClass.isInstance(o)) put((boolean) mBoolValue.invoke(o) ? 1f : 0f);
            else if (doubleOptionClass.isInstance(o)) put((float) (double) mDoubleValue.invoke(o));
            else put(0f);
        }
        Object street = fStreetRenderData.get(d);
        put(((List<?>) fStreetChars.get(street)).size());
        put((int) mTroveSize.invoke(fStreetLines.get(street)));
        put((int) mTroveSize.invoke(fStreetPolygon.get(street)));
        put((int) mTroveSize.invoke(fStreetTriangles.get(street)));
        put(((List<?>) fSymList.get(fSymbolsRenderData.get(d))).size());
        Object visited = fVisited.get(renderer);
        put(visited == null ? 0 : 1);

        putRef(renderer);
        putRef(fWorldMap.get(d));
        List<?> layers = (List<?>) fStyleLayers.get(fStyle.get(d));
        put(layers.size());
        for (int i = 0, n = layers.size(); i < n; i++) putRef(layers.get(i));
        return null;
    }

    private static void put(float v) {
        if (curN == cur.length) cur = Arrays.copyOf(cur, curN * 2);
        cur[curN++] = v;
    }

    private static void putRef(Object o) {
        if (curRN == curR.length) curR = Arrays.copyOf(curR, curRN * 2);
        curR[curRN++] = o;
    }

    private static boolean keyEquals(float[] a, int an, Object[] ar, int arn, float[] b, int bn, Object[] br, int brn) {
        if (an != bn || arn != brn) return false;
        for (int i = 0; i < an; i++) if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[i])) return false;
        for (int i = 0; i < arn; i++) if (ar[i] != br[i]) return false;
        return true;
    }


    // -------------------- record / replay --------------------

    private static boolean startRecord(Object d, long now) throws Throwable {
        if (!ensureFbo()) return false;
        prevRawFbo = (int) glGetInteger.invoke(GL_FRAMEBUFFER_BINDING);
        int tracked = (int) mCurrentId.invoke();
        if (!loggedBinding) {
            loggedBinding = true;
            System.out.println("[map-fbo] first record: bound framebuffer " + prevRawFbo + ", game tracks " + tracked);
        }
        // Through TextureFBO, so any nested start/endDrawing inside the base passes returns here.
        mStartDrawing.invoke(fbo, false, false);
        boolean scissor = (boolean) glIsEnabled.invoke(GL_SCISSOR_TEST);
        if (scissor) glDisable.invoke(GL_SCISSOR_TEST);
        glGetFloatv.invoke(GL_COLOR_CLEAR_VALUE, clearSaved);
        Object fill = fFill.get(d);
        glClearColor.invoke(fRgbaR.getFloat(fill), fRgbaG.getFloat(fill), fRgbaB.getFloat(fill), 1f);
        glClear.invoke(GL_COLOR_BUFFER_BIT);
        glClearColor.invoke(clearSaved[0], clearSaved[1], clearSaved[2], clearSaved[3]);
        if (scissor) glEnable.invoke(GL_SCISSOR_TEST);
        recording = true;
        recordStart = now;
        return true;
    }

    private static void endRecordBinding() throws Throwable {
        mEndDrawing.invoke(fbo);
        if ((int) glGetInteger.invoke(GL_FRAMEBUFFER_BINDING) != prevRawFbo)
            mBindFramebuffer.invoke(funcs, glFramebuffer, prevRawFbo);
    }

    /**
     * The tail of renderInternal from offset 405 on (Build 42.21/42.22 bytecode): state set-up,
     * renderPlayers, the outside-bounds paint, the world-bounds outline (on by default), user
     * annotations, flush, full-screen viewport. The debug cell grids in between are gated off, so
     * they never get here.
     */
    private static void replay(Object d) throws Throwable {
        composite(d);
        mapState();
        mRenderPlayers.invoke(d);
        Object worldMap = fWorldMap.get(d);
        mPaintOutside.invoke(d, (int) mMinX.invoke(worldMap), (int) mMinY.invoke(worldMap),
                (int) mMaxX.invoke(worldMap), (int) mMaxY.invoke(worldMap), fWorldScale.getFloat(d));
        if ((boolean) mBoolValue.invoke(fWorldBounds.get(fRenderer.get(d))))
            mRenderWorldBounds.invoke(d);
        if ((boolean) mGetBoolean.invoke(fRenderer.get(d), "Symbols"))
            mSymbolsRender.invoke(fSymbolsRenderData.get(d), d, true);
        mVboFlush.invoke(fVboLines.get(null));
        glViewport.invoke(0, 0, fCoreW.getInt(null), fCoreH.getInt(null));
    }

    private static void mapState() throws Throwable {
        glActiveTexture.invoke(33984);
        glTexEnvi.invoke(8960, 8704, 8448);
        glPolygonMode.invoke(1032, 6914);
        glEnable.invoke(GL_BLEND);
        Object ring = fRingBuffer.get(null);
        fRestoreTextures.setBoolean(ring, true);
        fRestoreVbos.setBoolean(ring, true);
    }

    /** Copies the map rectangle of the cached picture to the bound framebuffer, blending off. */
    private static void composite(Object d) throws Throwable {
        int x = fX.getInt(d), y = fY.getInt(d), w = fW.getInt(d), h = fH.getInt(d);
        int sw = fCoreW.getInt(null), sh = fCoreH.getInt(null);
        glViewport.invoke(0, 0, sw, sh);
        mSetOrtho2D.invoke(orthoMatrix, 0f, (float) sw, (float) sh, 0f);
        mPushLoad.invoke(GL_PROJECTION, orthoMatrix);
        mPushLoad.invoke(GL_MODELVIEW, identityMatrix);
        glDepthMask.invoke(false);
        glDisable.invoke(GL_BLEND);
        Object vbo = mVboGetInstance.invoke();
        mStartRun.invoke(vbo, fFormatPCU.get(vbo));
        mSetTextureId.invoke(vbo, texId);
        mSetMode.invoke(vbo, 7);
        // The picture is in GL window coordinates (row 0 at the bottom), the quad in UI coordinates.
        float u0 = x / (float) texWHW, u1 = (x + w) / (float) texWHW;
        float vTop = (sh - y) / (float) texHHW, vBottom = (sh - y - h) / (float) texHHW;
        mAddQuad.invoke(vbo, (float) x, (float) y, u0, vTop, (float) (x + w), (float) (y + h), u1, vBottom,
                0f, 1f, 1f, 1f, 1f);
        mEndRun.invoke(vbo);
        mVboFlush.invoke(vbo);
        mPopMatrix.invoke(GL_PROJECTION);
        mPopMatrix.invoke(GL_MODELVIEW);
        glEnable.invoke(GL_BLEND);
        mGlStateRestore.invoke();
        mForgetShader.invoke();
        glUseProgram.invoke(0);
        glViewport.invoke(x, sh - h - y, w, h);
    }

    private static boolean ensureFbo() throws Throwable {
        int sw = fCoreW.getInt(null), sh = fCoreH.getInt(null);
        if (fbo != null && (fboW != sw || fboH != sh)) {
            destroyFbo();
            valid = false;
        }
        if (fbo != null) return true;
        int prev = (int) glGetInteger.invoke(GL_FRAMEBUFFER_BINDING);
        try {
            tex = mNewTexture.invoke(sw, sh, 16);
            fbo = mNewFbo.invoke(tex, false);
            fboW = sw;
            fboH = sh;
            texId = mGetTextureId.invoke(tex);
            texWHW = (int) mWidthHW.invoke(tex);
            texHHW = (int) mHeightHW.invoke(tex);
            mBindFramebuffer.invoke(funcs, glFramebuffer, (int) mGetBufferId.invoke(fbo));
            int status = (int) mCheckStatus.invoke(funcs, glFramebuffer);
            if (status != (int) mGlComplete.invoke(funcs)) {
                System.out.println("[map-fbo] framebuffer incomplete (0x" + Integer.toHexString(status) + "), cache off");
                destroyFbo();
                off = true;
                return false;
            }
            System.out.println("[map-fbo] picture " + sw + "x" + sh + " in a " + texWHW + "x" + texHHW
                    + " texture (" + ((long) texWHW * texHHW * 4 >> 20) + " MB)");
            return true;
        } finally {
            mBindFramebuffer.invoke(funcs, glFramebuffer, prev);
        }
    }

    private static void destroyFbo() {
        Object f = fbo;
        fbo = null;
        tex = null;
        texId = null;
        if (f == null) return;
        try {
            mFboDestroy.invoke(f); // also frees the texture; runs inline on the render thread
        } catch (Throwable t) {
            System.out.println("[map-fbo] destroy failed: " + t);
        }
    }

    private static void fail(String where, Throwable t) {
        if (recording) {
            recording = false;
            try {
                endRecordBinding();
            } catch (Throwable ignored) {
            }
        }
        off = true;
        valid = false;
        System.out.println("[map-fbo] off for this session (" + where + "): " + t);
        destroyFbo();
    }

    private static void stats(long now) {
        if (statsAt == 0) {
            statsAt = now;
            return;
        }
        if (now - statsAt < STATS_NS) return;
        int frames = sLive + sGated + sRecord + sReplay;
        if (frames > 0) {
            System.out.println("[map-fbo] " + ((now - statsAt) / 1_000_000_000L) + "s: replay=" + sReplay
                    + " record=" + sRecord + " live=" + sLive + " gated=" + sGated + " incomplete=" + sIncomplete
                    + " | record avg " + (sRecord == 0 ? 0 : sRecordNs / sRecord / 1_000_000L) + " ms"
                    + ", replay avg " + (sReplay == 0 ? 0 : sReplayNs / sReplay / 1_000L) + " us");
        }
        statsAt = now;
        sLive = sGated = sRecord = sReplay = sIncomplete = 0;
        sRecordNs = sReplayNs = 0;
    }

    // -------------------- resolution --------------------

    private static void resolve(Class<?> drawer) {
        resolved = true;
        try {
            ClassLoader cl = drawer.getClassLoader();
            Class<?> wmr = Class.forName("zombie.worldMap.WorldMapRenderer", false, cl);
            Class<?> rgba = Class.forName("zombie.worldMap.styles.WorldMapStyleLayer$RGBAf", false, cl);
            Class<?> style = Class.forName("zombie.worldMap.styles.WorldMapStyle", false, cl);
            Class<?> visited = Class.forName("zombie.worldMap.WorldMapVisited", false, cl);
            Class<?> symbolsRD = Class.forName("zombie.worldMap.symbols.SymbolsRenderData", false, cl);
            Class<?> streetRD = Class.forName("zombie.worldMap.streets.StreetRenderData", false, cl);
            Class<?> worldMap = Class.forName("zombie.worldMap.WorldMap", false, cl);
            Class<?> core = Class.forName("zombie.core.Core", false, cl);
            Class<?> sprite = Class.forName("zombie.core.SpriteRenderer", false, cl);
            Class<?> ring = Class.forName("zombie.core.SpriteRenderer$RingBuffer", false, cl);
            Class<?> vboR = Class.forName("zombie.core.opengl.VBORenderer", false, cl);
            Class<?> vertexFormat = Class.forName("zombie.core.skinnedmodel.model.VertexBufferObject$VertexFormat", false, cl);
            Class<?> textureIdC = Class.forName("zombie.core.textures.TextureID", false, cl);
            Class<?> texture = Class.forName("zombie.core.textures.Texture", false, cl);
            Class<?> iTexture = Class.forName("zombie.interfaces.ITexture", false, cl);
            Class<?> textureFbo = Class.forName("zombie.core.textures.TextureFBO", false, cl);
            Class<?> fboFuncs = Class.forName("zombie.core.textures.IGLFramebufferObject", false, cl);
            Class<?> pzgl = Class.forName("zombie.core.opengl.PZGLUtil", false, cl);
            Class<?> glState = Class.forName("zombie.core.opengl.GLStateRenderThread", false, cl);
            Class<?> shaderHelper = Class.forName("zombie.core.ShaderHelper", false, cl);
            Class<?> matrix = Class.forName("org.joml.Matrix4f", false, cl);
            Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11", false, cl);
            Class<?> gl13 = Class.forName("org.lwjgl.opengl.GL13", false, cl);
            Class<?> gl20 = Class.forName("org.lwjgl.opengl.GL20", false, cl);
            boolOptionClass = Class.forName("zombie.config.BooleanConfigOption", false, cl);
            doubleOptionClass = Class.forName("zombie.config.DoubleConfigOption", false, cl);

            fX = field(drawer, "x"); fY = field(drawer, "y"); fW = field(drawer, "width"); fH = field(drawer, "height");
            fCenterX = field(drawer, "centerWorldX"); fCenterY = field(drawer, "centerWorldY");
            fZoomF = field(drawer, "zoomF"); fWorldScale = field(drawer, "worldScale");
            fModelView = field(drawer, "modelView"); fFill = field(drawer, "fill"); fStyle = field(drawer, "style");
            fRenderer = field(drawer, "renderer"); fWorldMap = field(drawer, "worldMap");
            fTriangulations = field(drawer, "triangulationsThisFrame");
            fSymbolsRenderData = field(drawer, "symbolsRenderData"); fStreetRenderData = field(drawer, "streetRenderData");
            fRgbaR = field(rgba, "r"); fRgbaG = field(rgba, "g"); fRgbaB = field(rgba, "b"); fRgbaA = field(rgba, "a");
            fStyleLayers = field(style, "layers");
            fOptions = field(wmr, "options");
            fVisited = field(wmr, "visited");
            fVisitedChanged = field(visited, "textureChanged");
            fSymList = field(symbolsRD, "symbols");
            fStreetChars = field(streetRD, "characters"); fStreetLines = field(streetRD, "lines");
            fStreetPolygon = field(streetRD, "polygon"); fStreetTriangles = field(streetRD, "triangles");
            fCoreW = field(core, "width"); fCoreH = field(core, "height");
            fRingBuffer = field(sprite, "ringBuffer");
            fRestoreTextures = field(ring, "restoreBoundTextures"); fRestoreVbos = field(ring, "restoreVbos");
            fVboLines = field(wmr, "vboLines");
            fFormatPCU = field(vboR, "formatPositionColorUv");
            // Debug views that draw after the base or read live state: never cached. WorldBounds is
            // not one of them - it is on by default and is redrawn in the live tail instead.
            gateOptions = new Field[]{field(wmr, "wireframe"), field(wmr, "cellGrid"), field(wmr, "cellGrid300"),
                    field(wmr, "waterFlow")};
            fWorldBounds = field(wmr, "worldBounds");
            mRenderWorldBounds = mh(drawer, "renderWorldBounds");

            mBoolValue = mh(boolOptionClass, "getValue");
            mDoubleValue = mh(doubleOptionClass, "getValue");
            mMatrixGet = mh(matrix, "get", float[].class);
            mTroveSize = mh(fStreetLines.getType(), "size");
            mGetBoolean = mh(wmr, "getBoolean", String.class);
            mMinX = mh(worldMap, "getMinXInSquares"); mMinY = mh(worldMap, "getMinYInSquares");
            mMaxX = mh(worldMap, "getMaxXInSquares"); mMaxY = mh(worldMap, "getMaxYInSquares");
            mRenderPlayers = mh(drawer, "renderPlayers");
            mPaintOutside = mh(drawer, "paintAreasOutsideBounds", int.class, int.class, int.class, int.class, float.class);
            mSymbolsRender = mh(symbolsRD, "render", drawer, boolean.class);
            mVboFlush = mh(vboR, "flush");
            mVboGetInstance = mh(vboR, "getInstance");
            mStartRun = mh(vboR, "startRun", vertexFormat);
            mSetTextureId = mh(vboR, "setTextureID", textureIdC);
            mSetMode = mh(vboR, "setMode", int.class);
            mAddQuad = mh(vboR, "addQuad", float.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class);
            mEndRun = mh(vboR, "endRun");
            mPushLoad = mh(pzgl, "pushAndLoadMatrix", int.class, matrix);
            mPopMatrix = mh(pzgl, "popMatrix", int.class);
            mGlStateRestore = mh(glState, "restore");
            mForgetShader = mh(shaderHelper, "forgetCurrentlyBound");
            mNewTexture = MethodHandles.lookup().unreflectConstructor(texture.getConstructor(int.class, int.class, int.class));
            mNewFbo = MethodHandles.lookup().unreflectConstructor(textureFbo.getConstructor(iTexture, boolean.class));
            mGetBufferId = mh(textureFbo, "getBufferId");
            mGetTextureId = mh(texture, "getTextureId");
            mWidthHW = mh(texture, "getWidthHW"); mHeightHW = mh(texture, "getHeightHW");
            mStartDrawing = mh(textureFbo, "startDrawing", boolean.class, boolean.class);
            mEndDrawing = mh(textureFbo, "endDrawing");
            mCurrentId = mh(textureFbo, "getCurrentID");
            mFboDestroy = mh(textureFbo, "destroy");
            mBindFramebuffer = mh(fboFuncs, "glBindFramebuffer", int.class, int.class);
            mCheckStatus = mh(fboFuncs, "glCheckFramebufferStatus", int.class);
            mGlFramebuffer = mh(fboFuncs, "GL_FRAMEBUFFER");
            mGlComplete = mh(fboFuncs, "GL_FRAMEBUFFER_COMPLETE");
            mSetOrtho2D = mh(matrix, "setOrtho2D", float.class, float.class, float.class, float.class);
            mIdentity = mh(matrix, "identity");

            glViewport = mh(gl11, "glViewport", int.class, int.class, int.class, int.class);
            glGetInteger = mh(gl11, "glGetInteger", int.class);
            glIsEnabled = mh(gl11, "glIsEnabled", int.class);
            glEnable = mh(gl11, "glEnable", int.class);
            glDisable = mh(gl11, "glDisable", int.class);
            glClearColor = mh(gl11, "glClearColor", float.class, float.class, float.class, float.class);
            glClear = mh(gl11, "glClear", int.class);
            glGetFloatv = mh(gl11, "glGetFloatv", int.class, float[].class);
            glTexEnvi = mh(gl11, "glTexEnvi", int.class, int.class, int.class);
            glPolygonMode = mh(gl11, "glPolygonMode", int.class, int.class);
            glDepthMask = mh(gl11, "glDepthMask", boolean.class);
            glActiveTexture = mh(gl13, "glActiveTexture", int.class);
            glUseProgram = mh(gl20, "glUseProgram", int.class);

            funcs = mh(textureFbo, "getFuncs").invoke();
            if (funcs == null) throw new IllegalStateException("no framebuffer support");
            glFramebuffer = (int) mGlFramebuffer.invoke(funcs);
            orthoMatrix = matrix.getConstructor().newInstance();
            identityMatrix = matrix.getConstructor().newInstance();
            mIdentity.invoke(identityMatrix);
            System.out.println("[map-fbo] resolved");
        } catch (Throwable t) {
            off = true;
            System.out.println("[map-fbo] unavailable on this build: " + t);
        }
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static MethodHandle mh(Class<?> c, String name, Class<?>... params) throws Exception {
        Method m;
        try {
            m = c.getDeclaredMethod(name, params);
        } catch (NoSuchMethodException e) {
            m = c.getMethod(name, params);
        }
        m.setAccessible(true);
        return MethodHandles.lookup().unreflect(m);
    }
}
