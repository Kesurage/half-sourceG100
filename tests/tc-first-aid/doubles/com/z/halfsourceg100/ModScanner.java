package com.z.halfsourceg100;

import java.io.File;

/**
 * TEST DOUBLE (tests/tc-first-aid) — shadows the real ModScanner ONLY inside
 * this test's javac invocation (the runner never puts android-app/src on the
 * compile path). Surface must mirror android-app/src/.../ModScanner.java:
 * ROOT (content root) and RESERVED (engine-owned first-level dirs). The real
 * class derives ROOT from Android's Environment; the double takes it from
 * the test.root system property so tests run against a temp tree.
 */
public final class ModScanner {

    public static final String ROOT =
            System.getProperty("test.root", "/tmp/hsg100-test/srceng");

    static final String[] RESERVED = {"hl2", "platform", "sourceengine", "bin"};

    private ModScanner() {}
}
