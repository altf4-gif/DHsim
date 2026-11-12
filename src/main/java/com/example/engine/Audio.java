package com.example.engine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Tiny MP3 playback helper.
 *
 * The actual decoder is JLayer (javazoom.jl.player.Player). It is loaded
 * through reflection on purpose: this class compiles even when
 * lib\jlayer-*.jar is missing. Without that jar the game simply runs
 * without sound instead of failing to build.
 *
 * Every sound runs on its own daemon thread, so several sounds may overlap
 * and the render loop is never blocked.
 */
public final class Audio {

    private static final String PLAYER_CLASS = "javazoom.jl.player.Player";
    private static final String JLAYER_URL =
            "https://repo1.maven.org/maven2/com/googlecode/soundlibs/jlayer/1.0.1.4/jlayer-1.0.1.4.jar";

    /** Absolute paths we already complained about, so we warn only once. */
    private static final Set<String> warnedPaths = new HashSet<>();

    private static boolean initDone;
    private static boolean jlayerAvailable;
    private static Constructor<?> playerCtor;
    private static Method playMethod;
    private static Method closeMethod;

    private Audio() {
    }

    /** Lazily resolves JLayer once and prints a clear hint if it is missing. */
    private static synchronized boolean jlayer() {
        if (initDone) return jlayerAvailable;
        initDone = true;
        try {
            Class<?> cls = Class.forName(PLAYER_CLASS);
            playerCtor = cls.getConstructor(InputStream.class);
            playMethod = cls.getMethod("play");
            closeMethod = cls.getMethod("close");
            jlayerAvailable = true;
            System.out.println("[AUDIO] JLayer found - mp3 playback enabled.");
        } catch (Throwable t) {
            jlayerAvailable = false;
            System.err.println("[AUDIO] ================================================");
            System.err.println("[AUDIO] JLayer not found on the classpath.");
            System.err.println("[AUDIO] The game runs fine, but WITHOUT sound.");
            System.err.println("[AUDIO] Fix: put this file into lib\\");
            System.err.println("[AUDIO]   " + JLAYER_URL);
            System.err.println("[AUDIO] then run build.bat again.");
            System.err.println("[AUDIO] ================================================");
        }
        return jlayerAvailable;
    }

    /** Plays an mp3 once and forgets about it. */
    public static Handle playOnce(String path) {
        Handle h = new Handle(path, false, 0f);
        h.start();
        return h;
    }

    /**
     * Loops an mp3 for at most {@code seconds} seconds.
     * Call {@link Handle#stop()} earlier to cut it short.
     */
    public static Handle playLoop(String path, float seconds) {
        Handle h = new Handle(path, true, seconds);
        h.start();
        return h;
    }

    private static boolean fileOk(String path) {
        File f = new File(path);
        if (f.isFile() && f.length() > 0) return true;
        synchronized (warnedPaths) {
            if (warnedPaths.add(path)) {
                System.err.println("[AUDIO] not found / empty: " + f.getAbsolutePath());
            }
        }
        return false;
    }

    /** One playing sound. */
    public static final class Handle {
        private final String path;
        private final boolean loop;
        private final float duration;
        private volatile boolean stopped;
        private volatile Object player;

        private Handle(String path, boolean loop, float duration) {
            this.path = path;
            this.loop = loop;
            this.duration = duration;
        }

        private void start() {
            if (!jlayer()) {
                stopped = true;
                return;
            }
            if (!fileOk(path)) {
                stopped = true;
                return;
            }
            Thread t = new Thread(this::run, "audio:" + path);
            t.setDaemon(true);
            t.start();
        }

        private void run() {
            long deadline = (loop && duration > 0f)
                    ? System.nanoTime() + (long) (duration * 1_000_000_000L)
                    : Long.MAX_VALUE;

            try {
                do {
                    if (stopped) break;
                    InputStream in = new BufferedInputStream(new FileInputStream(path));
                    try {
                        Object p = playerCtor.newInstance(in);
                        player = p;
                        playMethod.invoke(p);
                        player = null;
                        closeMethod.invoke(p);
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                    }
                } while (loop && !stopped && System.nanoTime() < deadline);
            } catch (Throwable t) {
                // Closing the player from another thread lands here - that is fine.
                if (!stopped) {
                    System.err.println("[AUDIO] " + path + " -> " + t);
                }
            }
        }

        public void stop() {
            stopped = true;
            Object p = player;
            if (p != null) {
                try { closeMethod.invoke(p); } catch (Throwable ignored) { }
            }
        }
    }
}
