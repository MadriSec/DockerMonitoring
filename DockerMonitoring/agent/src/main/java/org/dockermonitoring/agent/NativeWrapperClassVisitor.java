package org.dockermonitoring.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Visits one class: renames every native method whose name is in
 * {@code methodNames} by prefixing it with {@code $$dm$$_}, and
 * injects a Java wrapper with the original name that records the call and
 * delegates to the renamed native.
 *
 * <p>The renamed native still resolves to its original JNI symbol because
 * we register {@code $$dm$$_} with
 * {@code Instrumentation.setNativeMethodPrefix(...)}, so the JVM strips the
 * prefix when looking up the C-side symbol.
 */
final class NativeWrapperClassVisitor extends ClassVisitor {

    private static final String PREFIX = NativeWrapperAgent.NATIVE_METHOD_PREFIX;

    private static final String TRACKER_INTERNAL = "org/dockermonitoring/bootstrap/NativeMethodTracker";
    private static final String TRACKER_DESCRIPTOR = "Lorg/dockermonitoring/bootstrap/NativeMethodTracker;";

    private final String dotClassName;       // e.g. "com.example.Foo"
    private final String internalClassName;  // e.g. "com/example/Foo"
    private final Set<String> methodNames;

    /** Methods we rewrote in {@link #visitMethod} and still need to emit a wrapper for. */
    private final List<MethodInfo> wrapped = new ArrayList<>();

    NativeWrapperClassVisitor(ClassVisitor cv, String dotClassName, Set<String> methodNames) {
        super(Opcodes.ASM9, cv);
        this.dotClassName = dotClassName;
        this.internalClassName = dotClassName.replace('.', '/');
        this.methodNames = methodNames;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        boolean isNative = (access & Opcodes.ACC_NATIVE) != 0;
        if (isNative && methodNames.contains(name)) {
            wrapped.add(new MethodInfo(access, name, descriptor, signature, exceptions));
            // Rename the original native to its prefixed form. The JVM's
            // native-method-prefix support handles linking the prefixed
            // name back to the unprefixed JNI symbol in the .so.
            return super.visitMethod(access, PREFIX + name, descriptor, signature, exceptions);
        }
        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        for (MethodInfo m : wrapped) {
            emitWrapper(m);
        }
        super.visitEnd();
    }

    /**
     * Emits a non-native method with the original signature whose body is:
     * <pre>
     *   NativeMethodTracker.getInstance().recordInvocation("Class.method");
     *   return $$dm$$_method(args...);
     * </pre>
     */
    private void emitWrapper(MethodInfo m) {
        int wrapperAccess = m.access & ~Opcodes.ACC_NATIVE;
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        boolean isPrivate = (m.access & Opcodes.ACC_PRIVATE) != 0;

        MethodVisitor mv = super.visitMethod(wrapperAccess, m.name, m.descriptor,
                m.signature, m.exceptions);
        mv.visitCode();

        // --- Tracker call: NativeMethodTracker.getInstance().recordInvocation(key) ---
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TRACKER_INTERNAL, "getInstance",
                "()" + TRACKER_DESCRIPTOR, false);
        mv.visitLdcInsn(dotClassName + "." + m.name);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TRACKER_INTERNAL, "recordInvocation",
                "(Ljava/lang/String;)V", false);

        // --- Delegate: load args, call $$dm$$_<name>, return the result ---
        int localIdx = 0;
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0); // this
            localIdx = 1;
        }
        Type[] argTypes = Type.getArgumentTypes(m.descriptor);
        for (Type t : argTypes) {
            mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), localIdx);
            localIdx += t.getSize();
        }

        int invokeOpcode;
        if (isStatic) {
            invokeOpcode = Opcodes.INVOKESTATIC;
        } else if (isPrivate) {
            // Private methods are invoked via INVOKESPECIAL in all JDKs.
            invokeOpcode = Opcodes.INVOKESPECIAL;
        } else {
            // INVOKEVIRTUAL preserves virtual dispatch: if a subclass overrides
            // this native, its own wrapper chain (also injected by this
            // transformer on the subclass) takes over, which is what we want.
            invokeOpcode = Opcodes.INVOKEVIRTUAL;
        }
        mv.visitMethodInsn(invokeOpcode, internalClassName, PREFIX + m.name,
                m.descriptor, false);

        Type returnType = Type.getReturnType(m.descriptor);
        mv.visitInsn(returnType.getOpcode(Opcodes.IRETURN));

        mv.visitMaxs(0, 0); // COMPUTE_MAXS recomputes these
        mv.visitEnd();
    }

    /** Snapshot of a native method's ASM visit parameters. */
    private static final class MethodInfo {
        final int access;
        final String name;
        final String descriptor;
        final String signature;
        final String[] exceptions;

        MethodInfo(int access, String name, String descriptor,
                   String signature, String[] exceptions) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
            this.signature = signature;
            this.exceptions = exceptions;
        }
    }
}
