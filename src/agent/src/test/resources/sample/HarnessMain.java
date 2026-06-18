package sample;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifies the agent's bytecode transformation visibly, and exercises the
 * tracker end-to-end.
 *
 * <p>Run with:
 * <pre>
 *   java -javaagent:native-wrapper-agent.jar \
 *        -Ddockermonitoring.native.methods.file=natives.cfg \
 *        -Ddockermonitoring.output.file=runtime_native_methods.txt \
 *        sample.HarnessMain
 * </pre>
 */
public final class HarnessMain {

    public static void main(String[] args) throws Exception {
        inspectBytecode();
        exerciseWrappers();
        dumpTrackerState();
    }

    private static void inspectBytecode() {
        System.out.println("-- declared methods on FakeNative after agent --");
        Method[] ms = FakeNative.class.getDeclaredMethods();
        TreeSet<String> sorted = new TreeSet<>();
        for (Method m : ms) {
            sorted.add(String.format("%-40s native=%s static=%s",
                    m.getName(),
                    Modifier.isNative(m.getModifiers()),
                    Modifier.isStatic(m.getModifiers())));
        }
        sorted.forEach(System.out::println);
    }

    private static void exerciseWrappers() {
        System.out.println("-- exercising wrappers --");
        safeCall("doNothing", FakeNative::doNothing);
        FakeNative instance = new FakeNative();
        safeCall("echo",      () -> instance.echo(42));
        safeCall("upper",     () -> instance.upper("hello"));
    }

    private static void safeCall(String label, Runnable r) {
        try {
            r.run();
            System.out.println(label + ": ran (unexpected — no JNI lib linked)");
        } catch (UnsatisfiedLinkError e) {
            System.out.println(label + ": native call reached (UnsatisfiedLinkError as expected)");
        } catch (Throwable t) {
            System.out.println(label + ": threw " + t);
        }
    }

    @SuppressWarnings("unchecked")
    private static void dumpTrackerState() throws Exception {
        System.out.println("-- tracker state --");
        Class<?> trackerClass = Class.forName("org.dockermonitoring.bootstrap.NativeMethodTracker");
        Object instance = trackerClass.getMethod("getInstance").invoke(null);
        Set<String> recorded =
                (Set<String>) trackerClass.getMethod("getRecordedMethodNames").invoke(instance);
        System.out.println("recorded methods: " + new TreeSet<>(recorded));
    }
}
