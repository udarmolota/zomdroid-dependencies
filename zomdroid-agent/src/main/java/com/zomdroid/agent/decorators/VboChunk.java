package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Enlarges the chunk of Build 42's shared streaming renderer, {@code zombie.core.opengl.VBORenderer}.
 *
 * <p>VBORenderer collects vertices in a 4 KB buffer (BUFFER_SIZE = 4096, about 146 vertices of the
 * position+colour format) and flushes whenever it is full: one glBufferData for the vertices and one
 * for the indices, then the draws. The world map pushes every visible vertex through it every frame
 * (pan and zoom are baked into the vertex positions on the CPU), so an open map means ~500
 * glBufferData calls of ~4 KB per frame on an S25 and over 1000 on a 40-fps phone (NG_GL4ES probe,
 * 2026-11-18). The bytes are not the problem, the number of calls is.</p>
 *
 * <p>With {@code ZOMDROID_VBO_KB=<kilobytes>} (Env vars) or {@code -Dzomdroid.vboKb=<kilobytes>} the
 * vertex buffer gets that size and the index buffer a quarter of it; numElements, the flush limit,
 * is then taken from that size instead of from 4096. Same bytes, far fewer calls. Indices are one
 * 16-bit short per vertex (the vertex number), so the vertex count must stay below 65 536: the
 * size is capped at 512 KB, which with the smallest stride we know of (16 bytes) is 32 768
 * vertices. Off by default, and nothing is patched while off.</p>
 */
public final class VboChunk {
    public static final int KB = readKb();

    private VboChunk() {
    }

    private static int readKb() {
        String v = System.getenv("ZOMDROID_VBO_KB");
        if (v == null || v.isEmpty()) v = System.getProperty("zomdroid.vboKb", "");
        try {
            int kb = Integer.parseInt(v.trim());
            return kb <= 4 ? 0 : Math.min(kb, 512);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean enabled() {
        return KB > 0;
    }

    public static class create {
        @Advice.OnMethodExit
        public static void exit(@Advice.FieldValue(value = "elements", readOnly = false) ByteBuffer elements,
                                @Advice.FieldValue(value = "indices", readOnly = false) ByteBuffer indices) {
            elements = ByteBuffer.allocateDirect(VboChunk.KB * 1024).order(ByteOrder.nativeOrder());
            indices = ByteBuffer.allocateDirect(VboChunk.KB * 1024 / 4).order(ByteOrder.nativeOrder());
        }
    }

    // setFormat() can run before create() (startRun on a fresh renderer), so the limit is computed
    // from the configured size, not from the buffer that may not exist yet.
    public static class setFormat {
        @Advice.OnMethodExit
        public static void exit(@Advice.FieldValue("elementSize") int elementSize,
                                @Advice.FieldValue(value = "numElements", readOnly = false) int numElements) {
            if (elementSize > 0) numElements = VboChunk.KB * 1024 / elementSize;
        }
    }
}
