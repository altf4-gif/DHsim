package com.example.engine;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;

/**
 * Tiny MP3 playback helper with per-sound volume.
 *
 * JLayer is loaded through reflection, so the project still compiles and
 * runs without lib\jlayer-*.jar (just silent). Volume is applied by wrapping
 * JLayer's AudioDevice in a Java dynamic proxy that scales every PCM frame
 * before it goes to the sound card.
 */
public final class Audio {

    private static final String JLAYER_URL =
            "https://repo1.maven.org/maven2/com/googlecode/soundlibs/jlayer/1.0.1.4/jlayer-1.0.1.4.jar";

    private static final Set<String> warnedPaths = new HashSet<>();

    private static boolean initDone;
    private static boolean jlayerAvailable;

    private static Class<?> audioDeviceItf;
    private static Constructor<?> playerCtorWithStream;
    private static Constructor<?> playerCtorWithDevice;
    private static Constructor<?> jsAudioDeviceCtor;
    private static Method playMethod;
    private static Method closeMethod;

    private Audio() {
    }

    private static synchronized boolean jlayer() {
        if (initDone) return jlayerAvailable;
        initDone = true;
        try {
            Class<?> playerCls = Class.forName("javazoom.jl.player.Player");
            audioDeviceItf     = Class.forName("javazoom.jl.player.AudioDevice");
            Class<?> jsDevCls  = Class.forName("javazoom.jl.player.JavaSoundAudioDevice");

            playerCtorWithStream = playerCls.getConstructor(InputStream.class);
            playerCtorWithDevice = playerCls.getConstructor(InputStream.class, audioDeviceItf);
            jsAudioDeviceCtor    = jsDevCls.getDeclaredConstructor();

            playMethod  = playerCls.getMethod("play");
            closeMethod = playerCls.getMethod("close");

            jlayerAvailable = true;
            System.out.println("[AUDIO] JLayer found - mp3 playback enabled.");
        } catch (Throwable t) {
            jlayerAvailable = false;
            System.err.println("[AUDIO] ================================================");
            System.err.println("[AUDIO] JLayer not found - the game runs WITHOUT sound.");
            System.err.println("[AUDIO] Put this jar into lib\\ and rebuild:");
            System.err.println("[AUDIO]   " + JLAYER_URL);
            System.err.println("[AUDIO] ================================================");
        }
        return jlayerAvailable;
    }

    public static Handle playOnce(String path, float volume) {
        Handle h = new Handle(path, false, 0f, volume);
        h.start();
        return h;
    }

    public static Handle playLoop(String path, float seconds, float volume) {
        Handle h = new Handle(path, true, seconds, volume);
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

    /** Builds an AudioDevice proxy that scales every PCM frame by {@code volume}. */
    private static Object makeVolumeDevice(float volume) throws Exception {
        final Object real = jsAudioDeviceCtor.newInstance();
        final float v = Math.max(0f, Math.min(1f, volume));

        InvocationHandler h = (proxy, method, args) -> {
            if ("write".equals(method.getName()) && args != null && args.length == 3) {
                short[] samples = (short[]) args[0];
                int offs = (Integer) args[1];
                int len  = (Integer) args[2];
                short[] scaled = samples.clone();
                for (int i = offs; i < offs + len; i++) {
                    int s = (int) (scaled[i] * v);
                    if (s > 32767)  s = 32767;
                    if (s < -32768) s = -32768;
                    scaled[i] = (short) s;
                }
                args[0] = scaled;
            }
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return Proxy.newProxyInstance(
                audioDeviceItf.getClassLoader(),
                new Class<?>[]{ audioDeviceItf },
                h);
    }

    public static final class Handle {
        private final String path;
        private final boolean loop;
        private final float duration;
        private final float volume;
        private volatile boolean stopped;
        private volatile Object player;

        private Handle(String path, boolean loop, float duration, float volume) {
            this.path = path;
            this.loop = loop;
            this.duration = duration;
            this.volume = Math.max(0f, Math.min(1f, volume));
        }

        private void start() {
            if (volume <= 0.01f) { stopped = true; return; }
            if (!jlayer())      { stopped = true; return; }
            if (!fileOk(path))  { stopped = true; return; }

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
                        Object p;
                        if (volume < 0.999f) {
                            Object dev = makeVolumeDevice(volume);
                            p = playerCtorWithDevice.newInstance(in, dev);
                        } else {
                            p = playerCtorWithStream.newInstance(in);
                        }
                        player = p;
                        playMethod.invoke(p);
                        player = null;
                        closeMethod.invoke(p);
                    } finally {
                        try { in.close(); } catch (Exception ignored) { }
                    }
                } while (loop && !stopped && System.nanoTime() < deadline);
            } catch (Throwable t) {
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
