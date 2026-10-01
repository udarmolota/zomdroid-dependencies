package com.zomdroid.agent;

import com.zomdroid.agent.decorators.GL33Mask;
import com.zomdroid.agent.decorators.QuickSave;
import com.zomdroid.agent.decorators.ShaderUnit;
import com.zomdroid.agent.decorators.SpinIdle;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.pool.TypePool;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.named;

public class Main {
    // First member on purpose: static initialisers run in source order, and the fields below
    // already touch Byte Buddy. The jar no longer carries the Class File API bridge (see the shade
    // filter in pom.xml), so Byte Buddy must not pick it on Java 24+; its own ASM 9.8 reads the
    // Java 25 classes of Build 42.20. On Java 21 (Build 41) this is what Byte Buddy does anyway.
    // The key is Byte Buddy's own constant, so it follows the relocated package name.
    static {
        if (System.getProperty(net.bytebuddy.utility.OpenedClassReader.PROCESSOR_PROPERTY) == null)
            System.setProperty(net.bytebuddy.utility.OpenedClassReader.PROCESSOR_PROPERTY, "ASM_ONLY");
    }

    private static ClassLoader classLoader = Main.class.getClassLoader();
    private static TypePool typePool = TypePool.Default.of(classLoader);
    private static ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(classLoader);
    public static void premain(String args, Instrumentation inst) {
        System.out.println("Hello from zomdroid agent");

        if (args == null)
            args = "";

        String[] argsArray = args.split(",");
        for (String arg: argsArray) {

        }

        String renderer = System.getProperty("zomdroid.renderer");
        boolean isGL4ES = renderer.equals("GL4ES");

        try {
            if (isGL4ES) {
                new ByteBuddy().with(TypeValidation.DISABLED)
                        .rebase(typePool.describe("zombie.core.opengl.ShaderUnit").resolve(), locator)
                        .visit(Advice.to(ShaderUnit.loadShaderFile.class).on(named("loadShaderFile")))
                        .visit(Advice.to(ShaderUnit.preProcessShaderFile.class).on(named("preProcessShaderFile")))
                        .visit(Advice.to(ShaderUnit.processIncludeLine.class).on(named("processIncludeLine")))
                        .make()
                        .load(classLoader, ClassReloadingStrategy.of(inst));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // ZINK only: GL4ES and NG_GL4ES never report OpenGL 3.3, so the flag is already false there.
        if (renderer.startsWith("ZINK") && !Boolean.getBoolean("zomdroid.keepGL33")) {
            try {
                new ByteBuddy().with(TypeValidation.DISABLED)
                        .rebase(typePool.describe("org.lwjgl.opengl.GL").resolve(), locator)
                        .visit(Advice.to(GL33Mask.createCapabilities.class).on(named("createCapabilities")))
                        .make()
                        .load(classLoader, ClassReloadingStrategy.of(inst));
                System.out.println("[gl33-mask] armed");
            } catch (Exception e) {
                System.out.println("[gl33-mask] could not hook GL.createCapabilities: " + e);
            }
        }

        // Deliberately outside the renderer check above: quick save has nothing to do with which
        // renderer is in use, and GameWindow.frameStep exists on every build we support, Build 41
        // included. Its own try/catch, so failing here cannot take the shader patch down with it.
        try {
            new ByteBuddy().with(TypeValidation.DISABLED)
                    .rebase(typePool.describe("zombie.GameWindow").resolve(), locator)
                    .visit(Advice.to(QuickSave.frameStep.class).on(named("frameStep")))
                    // Same rebase as quick save: GameWindow must not be rebased twice.
                    .visit(Advice.to(SpinIdle.mainThreadStep.class).on(named("mainThreadStep")))
                    .make()
                    .load(classLoader, ClassReloadingStrategy.of(inst));
            System.out.println("[spin-idle] game thread armed (" + SpinIdle.IDLE_NANOS + " ns)");
        } catch (Exception e) {
            System.out.println("[quicksave] could not hook GameWindow.frameStep: " + e);
        }

        // RenderThread spins the same way; see SpinIdle for both loops.
        try {
            new ByteBuddy().with(TypeValidation.DISABLED)
                    .rebase(typePool.describe("zombie.core.SpriteRenderer").resolve(), locator)
                    .visit(Advice.to(SpinIdle.acquireStateForRendering.class)
                            .on(named("acquireStateForRendering")))
                    .make()
                    .load(classLoader, ClassReloadingStrategy.of(inst));
            System.out.println("[spin-idle] render thread armed (" + SpinIdle.IDLE_NANOS + " ns)");
        } catch (Exception e) {
            System.out.println("[spin-idle] could not hook SpriteRenderer.acquireStateForRendering: " + e);
        }
    }
}
