package android.util;

/** TEST DOUBLE (tests/tc-first-aid) — mirrors the android.util.Log surface
 *  TcFirstAid uses. Never shipped; compiled only inside this test's javac
 *  invocation. */
public final class Log {
    private Log() {}
    public static int i(String tag, String msg) { System.out.println("[I/" + tag + "] " + msg); return 0; }
    public static int e(String tag, String msg) { System.out.println("[E/" + tag + "] " + msg); return 0; }
    public static int w(String tag, String msg) { System.out.println("[W/" + tag + "] " + msg); return 0; }
}
