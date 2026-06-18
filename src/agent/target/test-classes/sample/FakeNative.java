package sample;

/**
 * A tiny test subject. These native methods have NO backing JNI library —
 * invoking them throws UnsatisfiedLinkError. That's fine for the test:
 * the wrapper we inject runs {@code NativeMethodTracker.recordInvocation}
 * BEFORE it attempts the native call, so the record is made regardless.
 */
public class FakeNative {

    public static native void doNothing();

    public native int echo(int x);

    public native String upper(String s);
}
