package org.dockermonitoring.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Class file transformer that rewrites native methods listed in
 * {@link ConfigLoader} into:
 * <ul>
 *   <li>a renamed native method carrying {@link NativeWrapperAgent#NATIVE_METHOD_PREFIX},
 *       which the JVM still resolves to the original JNI symbol thanks to
 *       {@code Instrumentation.setNativeMethodPrefix}, and</li>
 *   <li>a new non-native Java wrapper with the original name that calls
 *       {@code NativeMethodTracker.recordInvocation(...)} and then delegates
 *       to the renamed native.</li>
 * </ul>
 */
final class NativeWrapperTransformer implements ClassFileTransformer {

    /** dotClassName -> set of native method names to wrap. Immutable. */
    private final Map<String, Set<String>> targetMethods;

    NativeWrapperTransformer() {
        this.targetMethods = ConfigLoader.load();
    }

    Map<String, Set<String>> targets() {
        return targetMethods;
    }

    boolean isEmpty() {
        return targetMethods.isEmpty();
    }

    int targetClassCount() {
        return targetMethods.size();
    }

    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain domain,
                            byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }

        // Fast path: cheap package filter before even converting name format.
        // Classes loaded before premain (core JDK) cannot be retransformed to
        // add new methods, and the tracker lives under org.dockermonitoring.bootstrap so
        // recursively wrapping our own code would be nonsense.
        if (isSkippedPackage(className)) {
            return null;
        }

        String dotName = className.replace('/', '.');
        Set<String> methods = targetMethods.get(dotName);
        if (methods == null || methods.isEmpty()) {
            return null;
        }

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            // COMPUTE_MAXS is sufficient: the wrappers we inject are straight-
            // line code (no branches), so no StackMapTable frames are required.
            // COMPUTE_FRAMES would trigger classloading of referenced types via
            // Type.getCommonSuperClass, which is unsafe during transformation.
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
            NativeWrapperClassVisitor visitor = new NativeWrapperClassVisitor(cw, dotName, methods);
            cr.accept(visitor, 0);
            byte[] out = cw.toByteArray();
            Coverage.markWrapped(dotName, visitor.wrappedNames());
            System.err.println("[DockerMonitoring] wrapped native methods in " + dotName
                    + " (targets=" + methods.size() + ", wrapped=" + visitor.wrappedNames().size() + ")");
            return out;
        } catch (Throwable t) {
            // Never let a transformer failure bubble into the JVM's
            // ClassCircularityError handling — return null and leave the
            // class unchanged.
            System.err.println("[DockerMonitoring] transform failed for " + dotName + ": " + t);
            return null;
        }
    }

    /**
     * Returns true for classes that must NOT be transformed. Two categories:
     * <ol>
     *   <li>Bootstrap classes loaded before premain ({@code java/}, {@code javax/},
     *       {@code jdk/}, {@code sun/}) — retransformation cannot add the wrapper
     *       method these classes would need.
     *   <li>Our own agent classes — wrapping ourselves would recurse.
     * </ol>
     *
     * <p>{@code com/sun/jna/} is deliberately NOT skipped: JNA classes are
     * loaded by the application classloader (not bootstrap) and their native
     * methods are key instrumentation targets for many Java applications.
     */
    private static boolean isSkippedPackage(String internalName) {
        // Our own agent + bootstrap helper packages
        if (internalName.startsWith("org/dockermonitoring/")) {
            return true;
        }
        // Method handles: signature-polymorphic natives must stay native, and
        // the tracker's own code may bootstrap lambdas through these classes.
        if (internalName.startsWith("java/lang/invoke/")) {
            return true;
        }
        // JDK classes that load after premain can be wrapped like any other:
        // the transformer sees them at load time, before they are linked.
        // Classes loaded before premain never reach transform() (see
        // Coverage.markPreloaded). -Ddockermonitoring.wrap.jdk=false restores
        // the old behaviour of skipping the JDK entirely.
        if (WRAP_JDK) {
            return false;
        }
        if (internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("sun/")) {
            return true;
        }
        // com/sun/ EXCEPT com/sun/jna/ (JNA is app-classloaded, not bootstrap)
        if (internalName.startsWith("com/sun/")
                && !internalName.startsWith("com/sun/jna/")) {
            return true;
        }
        return false;
    }

    private static final boolean WRAP_JDK =
            !"false".equalsIgnoreCase(System.getProperty("dockermonitoring.wrap.jdk", "true"));
}
