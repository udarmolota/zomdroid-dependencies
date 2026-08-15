package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/**
 * Quick save on F10, hooked onto {@code zombie.GameWindow.frameStep}.
 *
 * <p>A crash costs the player everything since the last write to disk, and Project Zomboid writes
 * on its own terms - typically when you exit to the menu. So the launcher offers a button the
 * player presses when they judge themselves safe, and it performs exactly the write that exiting
 * would: {@code GameWindow.save(true)}.
 *
 * <p>The button is an ordinary on-screen control bound to F10, so the keystroke reaches the game
 * through the input path we already have, and a physical keyboard gets the same shortcut for free.
 * The game itself does nothing with F10 - this is what listens for it.
 *
 * <h3>Why here and not in a mod</h3>
 *
 * <p>A ten-line Lua mod can call the same {@code save(true)}, and one on the Workshop does. But
 * Project Zomboid records the active mod list inside each save, so a mod added later is not enabled
 * in a world that already exists - which is precisely the world worth protecting. An agent has no
 * such problem: it is loaded before the game starts, on every world, invisible to the mod list and
 * to servers, and the player cannot switch it off by accident.
 *
 * <h3>Everything is reflective</h3>
 *
 * <p>The agent is built without the game on its classpath, so the two calls are looked up by name
 * once and cached. Failure at any point disables the hook rather than the game: a save button that
 * silently does nothing is a bad day, a per-frame exception is a broken game.
 */
public class QuickSave {

    /** GLFW F10 - what our input layer sends, and what LWJGL 3 delivers to the game. */
    private static final int F10_GLFW = 299;
    /** LWJGL 2 F10, tried once if the array behind isKeyDown turns out to be the older, smaller one. */
    private static final int F10_LWJGL2 = 68;

    private static boolean resolved;
    private static boolean disabled;
    private static Method isKeyPressed;   // zombie.input.GameKeyboard#isKeyPressed(int)
    private static Method save;           // zombie.GameWindow#save(boolean)
    private static Method getPlayer;      // zombie.characters.IsoPlayer#getInstance()
    private static Method addText;        // zombie.characters.HaloTextHelper#addText(IsoPlayer, String)
    private static int keyCode = F10_GLFW;
    private static boolean saving;

    /**
     * Called at the end of every frame. Costs one boolean check once the hook is disabled, and a
     * single reflective call otherwise - {@code isKeyPressed} is edge triggered, so holding the key
     * down does not repeat.
     */
    public static void onFrame() {
        if (disabled) return;
        if (!resolved) resolve();
        if (disabled || saving) return;

        try {
            if (!(Boolean) isKeyPressed.invoke(null, keyCode)) return;
            // No player means no world: the main menu has nothing to write, and asking it to would
            // be a novel way to break the game.
            Object player = getPlayer.invoke(null);
            if (player == null) return;

            saving = true;
            long startedAt = System.currentTimeMillis();
            save.invoke(null, Boolean.TRUE);
            System.out.println("[quicksave] saved in " + (System.currentTimeMillis() - startedAt) + " ms");
            // The save blocks the frame for the best part of a second, so without a word on screen
            // the press reads as "nothing happened" and gets repeated.
            say(player, "Game saved");
        } catch (Throwable t) {
            sayFailed();
            // The key array is indexed without a bounds check, so a build whose key codes are the
            // older, smaller set throws here rather than returning false. Switch to that set once
            // and let the next frame try again; anything else turns the hook off for the session.
            if (keyCode == F10_GLFW && rootCause(t) instanceof ArrayIndexOutOfBoundsException) {
                keyCode = F10_LWJGL2;
                System.out.println("[quicksave] key code " + F10_GLFW + " out of range, using " + F10_LWJGL2);
            } else {
                disabled = true;
                System.out.println("[quicksave] disabled: " + rootCause(t));
            }
        } finally {
            saving = false;
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            ClassLoader cl = QuickSave.class.getClassLoader();
            isKeyPressed = Class.forName("zombie.input.GameKeyboard", false, cl)
                    .getMethod("isKeyPressed", int.class);
            save = Class.forName("zombie.GameWindow", false, cl)
                    .getMethod("save", boolean.class);
            Class<?> player = Class.forName("zombie.characters.IsoPlayer", false, cl);
            getPlayer = player.getMethod("getInstance");
            System.out.println("[quicksave] F10 armed");

            // The on-screen message is a nicety, so it is resolved separately and its absence is
            // not fatal. addText(IsoPlayer, String) is the one overload Build 41 and Build 42 share;
            // addGoodText exists only on 42.
            try {
                addText = Class.forName("zombie.characters.HaloTextHelper", false, cl)
                        .getMethod("addText", player, String.class);
            } catch (Throwable t) {
                System.out.println("[quicksave] no on-screen message on this build: " + t);
            }
        } catch (Throwable t) {
            disabled = true;
            System.out.println("[quicksave] unavailable on this build: " + t);
        }
    }

    private static void say(Object player, String text) {
        if (addText == null || player == null) return;
        try {
            addText.invoke(null, player, text);
        } catch (Throwable ignored) {
            // Never let the confirmation be the thing that breaks a successful save.
        }
    }

    /** A press that does nothing looks like a broken button, so failures say so too. */
    private static void sayFailed() {
        try {
            say(getPlayer.invoke(null), "Save failed");
        } catch (Throwable ignored) {
        }
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    /**
     * {@code zombie.GameWindow.frameStep} runs once per frame on the thread that owns the world,
     * which is the only thread a save may happen on.
     */
    public static class frameStep {
        @Advice.OnMethodExit
        public static void onExit() {
            QuickSave.onFrame();
        }
    }
}
