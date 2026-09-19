package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.util.concurrent.locks.LockSupport;

/**
 * Puts Project Zomboid's two busy-wait loops to sleep while they have nothing to do.
 *
 * <p>MainThread.mainLoop() calls GameWindow.mainThreadStep() and then Thread.yield(), over and
 * over. mainThreadStep() only runs a frame once enough time has accumulated for the FPS limit;
 * every other call returns within microseconds. RenderThread.renderLoop() does the same around
 * renderStep(), which finds no new frame state most of the time. Thread.yield() is sched_yield on
 * Android, so each loop keeps a whole CPU core at 100% and holds its cluster at top clock: the
 * phone heats up even on the main menu (profiled 2026-09-19: 93-95% of the game thread in
 * sched_yield).</p>
 *
 * <p>Both hooks sleep only after an idle pass, so a frame is never delayed by more than the park.
 * {@code -Dzomdroid.idleNanos=<nanoseconds>} changes the park; zero turns both hooks off.</p>
 */
public final class SpinIdle {
    public static final long IDLE_NANOS = Math.max(0L, Long.getLong("zomdroid.idleNanos", 1_000_000L));

    // A mainThreadStep() that ran a frame takes milliseconds; an idle one takes microseconds.
    public static final long IDLE_PASS_NANOS = 100_000L;

    private SpinIdle() {
    }

    public static class mainThreadStep {
        @Advice.OnMethodEnter
        public static long enter() {
            return System.nanoTime();
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter long start) {
            if (IDLE_NANOS > 0L && System.nanoTime() - start < IDLE_PASS_NANOS) {
                LockSupport.parkNanos(IDLE_NANOS);
            }
        }
    }

    // renderStep() returns true either way, so the idle pass is read from here instead: no frame
    // state from the game thread means there is nothing to draw yet.
    public static class acquireStateForRendering {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(typing = net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC) Object state) {
            if (state == null && IDLE_NANOS > 0L) {
                LockSupport.parkNanos(IDLE_NANOS);
            }
        }
    }
}
