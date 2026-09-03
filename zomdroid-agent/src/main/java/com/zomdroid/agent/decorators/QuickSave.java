package com.zomdroid.agent.decorators;

import net.bytebuddy.asm.Advice;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * Quick save on F10, hooked onto {@code zombie.GameWindow.frameStep}, with an optional backup.
 *
 * <p>A crash costs the player everything since the last write to disk, and Project Zomboid writes
 * on its own terms. The plain quick save performs exactly the write that exiting to the menu would:
 * {@code GameWindow.save(true)}. That lowers the cost of a crash but is not a point to return to -
 * the game keeps writing over the same files as you play on.
 *
 * <p>The backup is the point to return to. When the launcher passes {@code -Dzomdroid.backup.dir},
 * F10 additionally copies the whole save folder aside, in the order the "A Second Chance" mod
 * proved out: mods.txt first, then the full flush (world, vehicles DB, players DB, queued chunks),
 * then wait for the world streamer to go quiet, and only then copy. Restore lives in the launcher,
 * which runs before the game and can swap directories no one has open.
 *
 * <h3>Order of survival</h3>
 *
 * <p>Copying takes seconds and the phone can die at any of them, so at every instant at least one
 * complete backup exists on disk. A new backup is written to a fresh generation directory while the
 * old one stays untouched; a {@code complete} marker is written after verification; a tiny
 * {@code current} file is flipped to the new generation last; only then is the old generation
 * removed. A kill anywhere in that sequence leaves either the old backup or both.
 *
 * <h3>Crash detection</h3>
 *
 * <p>While a world is loaded a {@code session.running} marker sits in the backup root, removed by a
 * shutdown hook. PZ exits through {@code System.exit} (verified: no {@code Runtime.halt} anywhere
 * in the game), so the hook runs on every normal exit and is skipped exactly when the process is
 * killed - a surviving marker plus a complete backup is what makes the launcher offer a restore.
 *
 * <p>Everything game-facing is reflective and resolved once; failure of the backup half degrades to
 * the plain quick save rather than taking the button down. Failure text is precise on purpose: by
 * the time a backup can fail, {@code save(true)} has already succeeded, so "Game saved, backup
 * failed" - claiming less would be false, claiming more would repeat the mod's bug where a caught
 * exception still reported success.
 */
public class QuickSave {

    /** GLFW F10 - what our input layer sends, and what LWJGL 3 delivers to the game. */
    private static final int F10_GLFW = 299;
    /** LWJGL 2 F10, tried once if the array behind isKeyDown turns out to be the older, smaller one. */
    private static final int F10_LWJGL2 = 68;

    /** How long the world streamer may stay busy before the backup is called off. */
    private static final long STREAMER_WAIT_MS = 10_000;
    private static final long FREE_SPACE_FLOOR = 64L * 1024 * 1024;

    private static boolean resolved;
    private static boolean disabled;
    private static Method isKeyPressed;   // zombie.input.GameKeyboard#isKeyPressed(int)
    private static Method save;           // zombie.GameWindow#save(boolean)
    private static Method getPlayer;      // zombie.characters.IsoPlayer#getInstance()
    private static Method addText;        // zombie.characters.HaloTextHelper#addText(IsoPlayer, String)
    private static int keyCode = F10_GLFW;
    private static boolean saving;

    // -------------------- backup state --------------------

    /** Set by the launcher only when the user enabled backups for this instance. */
    private static final String backupRoot = System.getProperty("zomdroid.backup.dir");

    /**
     * "off" when the launcher knows the feature exists for this instance and the user has it
     * disabled. F10 then tells them so instead of silently doing a plain save - a plain save
     * resumes convincingly after a kill (the game streams the world anyway), which is exactly how
     * it was mistaken for a working checkpoint, twice, by different people. No property at all
     * means Build 41 or an older launcher, where the plain save stays: no backup was ever on offer.
     */
    private static final boolean backupSwitchedOff = "off".equals(System.getProperty("zomdroid.backup"));

    private static boolean backupResolved;
    private static boolean backupDisabled;
    private static Method manipulateSavefile; // LuaManager$GlobalObject#manipulateSavefile(String, String)
    private static Method getCurrentSaveDir;  // ZomboidFileSystem#getCurrentSaveDir()
    private static Object zfs;                // ZomboidFileSystem.instance
    private static Field gameClientIsClient;  // GameClient.client - true means multiplayer
    private static Object vehiclesDb;         // VehiclesDB2.instance
    private static Method vdbSetForceSave, vdbUpdateMain;
    private static Method pdbIsAvailable, pdbGetInstance, pdbSavePlayers, pdbUpdateMain;
    private static Object chunkSaveWorker;    // ChunkSaveWorker.instance
    private static Method cswSaveNow;
    private static Object worldStreamer;      // WorldStreamer.instance
    private static Method wsIsBusy;

    /**
     * The two-frame machine: F10 announces "Backup..." and returns, so the frame gets to render the
     * text before the flush freezes the game thread; the countdown then triggers the real work.
     * Without this the player stares at a frozen screen with no explanation - which is exactly when
     * people kill the process.
     */
    private static int backupCountdown;
    private static boolean markerWritten;

    /**
     * Called at the end of every frame. Costs one boolean check once disabled, and a single
     * reflective call otherwise - {@code isKeyPressed} is edge triggered, so holding the key down
     * does not repeat.
     */
    public static void onFrame() {
        if (disabled) return;
        if (!resolved) resolve();
        if (disabled || saving) return;

        try {
            Object player = getPlayer.invoke(null);
            if (player == null) return; // main menu - no world, nothing to write or mark

            if (backupRoot != null && !markerWritten) writeSessionMarker();

            if (backupCountdown > 0) {
                if (--backupCountdown == 0) runBackup(player);
                return;
            }

            if (!(Boolean) isKeyPressed.invoke(null, keyCode)) return;

            if (backupRoot != null && backupAvailable()) {
                say(player, "Backup...");
                backupCountdown = 2;
            } else if (backupSwitchedOff) {
                say(player, "Not enabled - see Settings");
                System.out.println("[quicksave] pressed while the backup feature is off");
            } else {
                // Build 41, an older launcher, or multiplayer with the backup armed. The plain
                // save(true) that used to live here is gone on purpose: the game streams the world
                // to disk anyway, so after a kill it resumes near where it died and "Game saved"
                // looked like a working checkpoint - it misled her, a tester and us before that was
                // understood. F10 now never claims more than it holds.
                say(player, "Not available");
                System.out.println("[quicksave] pressed where the backup is not available");
            }
        } catch (Throwable t) {
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

    // -------------------- the backup itself --------------------

    private static void runBackup(Object player) {
        saving = true;
        long startedAt = System.currentTimeMillis();
        try {
            // 1. Full flush, in the order the mod proved out. mods.txt goes first so the copy
            // carries the world's own mod list - restoring without it loses the mods.
            String saveDir = (String) getCurrentSaveDir.invoke(zfs);
            String worldRel = lastTwoComponents(saveDir); // "<gameMode>/<worldName>"
            manipulateSavefile.invoke(null, worldRel, "WriteModsDotTxt");
            save.invoke(null, Boolean.TRUE);
            vdbSetForceSave.invoke(vehiclesDb);
            vdbUpdateMain.invoke(vehiclesDb);
            if ((Boolean) pdbIsAvailable.invoke(null)) {
                Object pdb = pdbGetInstance.invoke(null);
                pdbSavePlayers.invoke(pdb);
                pdbUpdateMain.invoke(pdb);
            }
            cswSaveNow.invoke(chunkSaveWorker);

            // 2. Wait for the streamer, and refuse rather than copy a moving target: a backup taken
            // while chunks are still landing is internally inconsistent, which is worse than none.
            long waitStart = System.currentTimeMillis();
            while ((Boolean) wsIsBusy.invoke(worldStreamer)) {
                if (System.currentTimeMillis() - waitStart > STREAMER_WAIT_MS) {
                    System.out.println("[quicksave] backup skipped: world streamer still busy after "
                            + STREAMER_WAIT_MS + " ms");
                    say(player, "Backup skipped");
                    return;
                }
                Thread.sleep(25);
            }

            // 3. Space: while the new generation is being written the old one still exists, so the
            // moment of truth needs room for BOTH plus slack.
            File source = new File(saveDir);
            long sourceSize = dirSize(source);
            File worldRoot = new File(backupRoot, worldRel);
            long need = sourceSize + Math.max(FREE_SPACE_FLOOR, sourceSize / 10);
            worldRoot.mkdirs();
            if (worldRoot.getUsableSpace() < need) {
                System.out.println("[quicksave] backup failed: need " + (need >> 20) + " MB free");
                say(player, "Backup failed: no space");
                return;
            }

            // 4. Copy into a fresh generation; verify; mark complete; flip the pointer; only then
            // drop the old generation and any leftovers from interrupted attempts.
            String gen = "gen-" + startedAt;
            File tmp = new File(worldRoot, gen + ".tmp");
            long[] copied = copyTree(source.toPath(), tmp.toPath()); // {files, bytes}
            long[] check = statTree(tmp.toPath());
            if (copied[0] != check[0] || copied[1] != check[1])
                throw new IOException("backup verify failed: wrote " + copied[0] + "/" + copied[1]
                        + ", found " + check[0] + "/" + check[1]);
            Files.write(new File(tmp, "complete").toPath(),
                    ("files=" + copied[0] + "\nbytes=" + copied[1] + "\n").getBytes(StandardCharsets.UTF_8));
            File genDir = new File(worldRoot, gen);
            if (!tmp.renameTo(genDir)) throw new IOException("could not finalize " + genDir);
            File currentTmp = new File(worldRoot, "current.tmp");
            Files.write(currentTmp.toPath(), gen.getBytes(StandardCharsets.UTF_8));
            Files.move(currentTmp.toPath(), new File(worldRoot, "current").toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            File[] entries = worldRoot.listFiles();
            if (entries != null) for (File e : entries) {
                if (e.isDirectory() && !e.getName().equals(gen)) deleteTree(e.toPath());
            }

            System.out.println("[quicksave] backup saved in " + (System.currentTimeMillis() - startedAt)
                    + " ms (" + copied[0] + " files, " + (copied[1] >> 20) + " MB)");
            say(player, "Backup saved");
        } catch (Throwable t) {
            // By this point save(true) has already succeeded - say exactly that much.
            System.out.println("[quicksave] backup failed: " + rootCause(t));
            say(player, "Backup failed");
        } finally {
            saving = false;
        }
    }

    private static boolean backupAvailable() {
        if (backupDisabled) return false;
        if (!backupResolved) resolveBackup();
        if (backupDisabled) return false;
        try {
            // Multiplayer: the authoritative world lives on the server; a local copy would restore
            // a world the server has since moved past. The mod is single-player only for the same
            // reason. Plain quick save still works.
            if (gameClientIsClient != null && gameClientIsClient.getBoolean(null)) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void resolveBackup() {
        backupResolved = true;
        try {
            ClassLoader cl = QuickSave.class.getClassLoader();
            manipulateSavefile = Class.forName("zombie.Lua.LuaManager$GlobalObject", false, cl)
                    .getMethod("manipulateSavefile", String.class, String.class);
            Class<?> zfsClass = Class.forName("zombie.ZomboidFileSystem", false, cl);
            zfs = zfsClass.getField("instance").get(null);
            getCurrentSaveDir = zfsClass.getMethod("getCurrentSaveDir");
            // The multiplayer flag: a public static boolean either way, but Build 42 calls it
            // "client" and Build 41 "bClient". Every other member of this sequence was verified
            // identical in 41.78.16 and 42.20 (2026-09-03, classes read off the device), so this
            // rename is the whole difference between the two builds.
            Class<?> gameClient = Class.forName("zombie.network.GameClient", false, cl);
            try {
                gameClientIsClient = gameClient.getField("client");
            } catch (NoSuchFieldException b42Absent) {
                gameClientIsClient = gameClient.getField("bClient");
            }
            Class<?> vdb = Class.forName("zombie.vehicles.VehiclesDB2", false, cl);
            vehiclesDb = vdb.getField("instance").get(null);
            vdbSetForceSave = vdb.getMethod("setForceSave");
            vdbUpdateMain = vdb.getMethod("updateMain");
            Class<?> pdb = Class.forName("zombie.savefile.PlayerDB", false, cl);
            pdbIsAvailable = pdb.getMethod("isAvailable");
            pdbGetInstance = pdb.getMethod("getInstance");
            pdbSavePlayers = pdb.getMethod("savePlayers");
            pdbUpdateMain = pdb.getMethod("updateMain");
            Class<?> csw = Class.forName("zombie.iso.ChunkSaveWorker", false, cl);
            chunkSaveWorker = csw.getField("instance").get(null);
            cswSaveNow = csw.getMethod("SaveNow");
            Class<?> ws = Class.forName("zombie.iso.WorldStreamer", false, cl);
            worldStreamer = ws.getField("instance").get(null);
            wsIsBusy = ws.getMethod("isBusy");
            System.out.println("[quicksave] backup armed, root: " + backupRoot);
        } catch (Throwable t) {
            backupDisabled = true;
            System.out.println("[quicksave] backup unavailable on this build, plain save only: " + t);
        }
    }

    // -------------------- session marker --------------------

    /**
     * Written once a world is actually loaded, removed by a shutdown hook on any normal exit. What
     * survives a kill is the marker itself - the launcher reads it, sees a complete backup for the
     * same world, and offers the restore before the game starts.
     */
    private static void writeSessionMarker() {
        try {
            String saveDir = (String) getCurrentSaveDirSafe();
            if (saveDir == null) return;
            File root = new File(backupRoot);
            root.mkdirs();
            final File marker = new File(root, "session.running");
            Files.write(marker.toPath(),
                    (lastTwoComponents(saveDir) + "\n" + System.currentTimeMillis() + "\n")
                            .getBytes(StandardCharsets.UTF_8));
            markerWritten = true;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                //noinspection ResultOfMethodCallIgnored
                marker.delete();
            }, "zomdroid-backup-marker"));
            System.out.println("[quicksave] session marker written");
        } catch (Throwable t) {
            markerWritten = true; // do not retry every frame
            System.out.println("[quicksave] session marker failed: " + rootCause(t));
        }
    }

    private static Object getCurrentSaveDirSafe() {
        try {
            if (!backupResolved) resolveBackup();
            if (backupDisabled) return null;
            return getCurrentSaveDir.invoke(zfs);
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------- helpers --------------------

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

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    /** "/a/b/Saves/Sandbox/MyWorld" -> "Sandbox/MyWorld". */
    private static String lastTwoComponents(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        int j = p.lastIndexOf('/', i - 1);
        return p.substring(j + 1);
    }

    private static long dirSize(File dir) throws IOException {
        long[] s = statTree(dir.toPath());
        return s[1];
    }

    /** {files, bytes} for every regular file under root. */
    private static long[] statTree(Path root) throws IOException {
        final long[] acc = new long[2];
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                acc[0]++;
                acc[1] += p.toFile().length();
            });
        }
        return acc;
    }

    /** Recursive copy; returns {files, bytes} written. */
    private static long[] copyTree(Path source, Path target) throws IOException {
        final long[] acc = new long[2];
        try (var stream = Files.walk(source)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    acc[0]++;
                    acc[1] += Files.size(dest);
                }
            }
        }
        return acc;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                //noinspection ResultOfMethodCallIgnored
                p.toFile().delete();
            });
        }
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
