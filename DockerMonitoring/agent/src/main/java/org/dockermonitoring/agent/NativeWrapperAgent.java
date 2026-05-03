package org.dockermonitoring.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarFile;

/**
 * Premain entry point.
 *
 * <p>Responsibilities (in order):
 * <ol>
 *   <li>Extract the embedded {@code tracker-boot.jar} to a temp file and
 *       append it to the bootstrap classloader's search path. That JAR
 *       contains only {@code org.dockermonitoring.bootstrap.NativeMethodTracker}, so
 *       exactly one class — the tracker — becomes visible from every
 *       classloader. The agent's own classes stay put in the system CL.
 *   <li>Tell the JVM to prefix native-method symbol lookups with
 *       {@code $$dm$$_}. This lets us rename existing native methods
 *       without recompiling the {@code .so}: the JVM strips the prefix when
 *       it fails to find the prefixed symbol and retries with the original
 *       name, which still exists in the native library.
 *   <li>Register a {@link java.lang.instrument.ClassFileTransformer} that
 *       rewrites matching native methods: the original {@code nativeFoo}
 *       is renamed to {@code $$dm$$_nativeFoo} (still native), and
 *       a new Java wrapper {@code nativeFoo} is injected that records the
 *       call and then delegates to the renamed native.
 * </ol>
 *
 * <p>Why a separate bootstrap JAR (instead of appending this JAR to
 * bootstrap): if the whole agent JAR is on bootstrap, the agent's own
 * package-private classes end up being defined by the bootstrap
 * classloader on first use — delegation means the JVM asks bootstrap
 * before the system CL — which splits access control across classloaders
 * and throws {@code IllegalAccessError}. The embedded-JAR approach makes
 * only the tracker reachable via bootstrap and leaves everything else in
 * the system CL where it was loaded.
 *
 * <p>The set of classes/methods to wrap is loaded from a file whose path is
 * given by the {@code dockermonitoring.native.methods.file} system property. Output
 * lives at {@code dockermonitoring.output.file}; both are read by
 * {@link org.dockermonitoring.bootstrap.NativeMethodTracker} and {@link ConfigLoader}.
 */
public final class NativeWrapperAgent {

    static final String NATIVE_METHOD_PREFIX = "$$dm$$_";

    /** Path, inside this JAR, of the embedded tracker-only mini-JAR. */
    private static final String TRACKER_BOOT_RESOURCE = "org/dockermonitoring/boot/tracker-boot.jar";

    private NativeWrapperAgent() {}

    public static void premain(String args, Instrumentation inst) throws Exception {
        Path trackerJar = extractTrackerBootJar();
        inst.appendToBootstrapClassLoaderSearch(new JarFile(trackerJar.toFile()));

        NativeLibraryMonitor.startIfEnabled();
        OpenTelemetryBridge.startIfEnabled();

        NativeWrapperTransformer transformer = new NativeWrapperTransformer();

        if (transformer.isEmpty()) {
            System.err.println("[DockerMonitoring] no native methods to wrap "
                    + "(check -Ddockermonitoring.native.methods.file); native wrapping disabled");
            PrometheusExporter.startIfEnabled();
            return;
        }

        inst.addTransformer(transformer, true);
        inst.setNativeMethodPrefix(transformer, NATIVE_METHOD_PREFIX);

        PrometheusExporter.startIfEnabled();

        System.err.println("[DockerMonitoring] premain loaded;"
                + " prefix=" + NATIVE_METHOD_PREFIX
                + "; classes=" + transformer.targetClassCount()
                + "; tracker=" + trackerJar);
    }

    /**
     * Copies the embedded {@code tracker-boot.jar} resource out of the
     * agent JAR into a temp file on disk. Returns the temp file path;
     * {@code appendToBootstrapClassLoaderSearch} needs a real file, not
     * an in-memory stream.
     */
    private static Path extractTrackerBootJar() throws IOException {
        ClassLoader cl = NativeWrapperAgent.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(TRACKER_BOOT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "embedded resource not found inside agent JAR: "
                                + TRACKER_BOOT_RESOURCE
                                + " — was the agent built with the "
                                + "build-tracker-boot-jar antrun execution?");
            }
            Path out = Files.createTempFile("dockermonitoring-tracker-", ".jar");
            out.toFile().deleteOnExit();
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
    }
}
