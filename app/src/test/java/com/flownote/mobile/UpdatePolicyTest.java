package com.flownote.mobile;

/** Pure JVM checks: run with scripts/test-app-updates.mjs; no Android service or local server. */
public final class UpdatePolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        check(UpdatePolicy.compareVersions("1.10.0", "1.9.0") > 0);
        check(UpdatePolicy.compareVersions("1.1.0", "1.1.0") == 0);
        check(UpdatePolicy.compareVersions("1.0.0", "1.1.0") < 0);
        for (String bad : new String[]{"1.0.0-beta", "01.1.0", "../x", "99999999999999999999.0.0"}) {
            try { UpdatePolicy.compareVersions(bad, "1.0.0"); throw new AssertionError(); }
            catch (IllegalArgumentException expected) { }
        }
        check(UpdatePolicy.allowedArtifact("https://flownote-react.vercel.app/downloads/flownote-1.1.0.apk", "production", "1.1.0"));
        for (String bad : new String[]{"http://flownote-react.vercel.app/downloads/flownote-1.1.0.apk", "https://evil.example/a.apk", "https://flownote-react.vercel.app@evil.example/downloads/flownote-1.1.0.apk", "https://flownote-react.vercel.app:444/downloads/flownote-1.1.0.apk", "https://flownote-react.vercel.app/downloads/flownote-1.0.0.apk"}) check(!UpdatePolicy.allowedArtifact(bad, "production", "1.1.0"));
        check(!UpdatePolicy.allowedArtifact("https://flownote-react.vercel.app/downloads/flownote-1.1.0.apk", "staging", "1.1.0"));
        var pair = java.security.KeyPairGenerator.getInstance("RSA"); pair.initialize(2048); var keys = pair.generateKeyPair();
        var signature = java.security.Signature.getInstance("SHA256withRSA"); byte[] payload = "release".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        signature.initSign(keys.getPrivate()); signature.update(payload); byte[] signed = signature.sign();
        check(UpdatePolicy.verify(payload, signed, keys.getPublic().getEncoded()));
        check(!UpdatePolicy.verify("tampered".getBytes(), signed, keys.getPublic().getEncoded()));
        System.out.println("PASS Android update version, channel, artifact origin and signature policy");
    }
}
