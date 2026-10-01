package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.lang.reflect.Field;

/**
 * Keeps Project Zomboid on its OpenGL 2.1 code path under ZINK.
 *
 * <p>PZ picks its rendering path from LWJGL's {@code GLCapabilities.OpenGL33} flag
 * ({@code Core.getUseOpenGL21()} is {@code !OpenGL33}; {@code Particles.init} and the texture
 * compression option read the flag too). LWJGL sets a version flag only when every function of
 * that version resolves. libzfa.so used to miss the three GL_ARB_timer_query functions, so on
 * ZINK the flag was false and the game always ran the 2.1 path, the one GL4ES and NG_GL4ES run.
 * Exporting those functions for PZ3D (zomdroid-dependencies a4180ed, launcher 3ff4be1,
 * 2026-09-27) turned the flag on, and the game switched to its 3.3 path, which breaks on ZINK:
 * {@code IsoPuddles$RenderToChunkTexture} finds no shader and every frame is cut short, leaving
 * garbage textures in rain and snow (Adreno 830, Build 42.21, 2026-10-01).</p>
 *
 * <p>The flag is cleared on the capabilities object LWJGL hands out; the function pointers stay,
 * so mods that call GL 3.3 functions directly keep working. {@code -Dzomdroid.keepGL33=true}
 * leaves the flag alone.</p>
 */
public final class GL33Mask {
    private static volatile boolean reported;

    private GL33Mask() {
    }

    public static void mask(Object capabilities) {
        if (capabilities == null) return;
        try {
            Field field = capabilities.getClass().getField("OpenGL33");
            if (!field.getBoolean(capabilities)) return;
            field.setAccessible(true);
            field.setBoolean(capabilities, false);
            if (!reported) {
                reported = true;
                System.out.println("[gl33-mask] OpenGL33 reported as false: the game stays on its 2.1 path");
            }
        } catch (Throwable t) {
            System.out.println("[gl33-mask] could not clear OpenGL33: " + t);
        }
    }

    public static class createCapabilities {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object capabilities) {
            GL33Mask.mask(capabilities);
        }
    }
}
