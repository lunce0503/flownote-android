package com.flownote.mobile;

import java.net.URI;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

final class UpdatePolicy {
    static int[] parts(String version) {
        if (version == null || !version.matches("(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})\\.(0|[1-9]\\d{0,5})")) throw new IllegalArgumentException("Invalid release version");
        String[] raw = version.split("\\."); return new int[]{Integer.parseInt(raw[0]), Integer.parseInt(raw[1]), Integer.parseInt(raw[2])};
    }
    static int compareVersions(String a, String b) {
        int[] left = parts(a), right = parts(b);
        for (int i = 0; i < 3; i++) if (left[i] != right[i]) return Integer.compare(left[i], right[i]);
        return 0;
    }
    static String origin(String channel) {
        if ("production".equals(channel)) return "https://flownote-react.vercel.app";
        if ("staging".equals(channel)) return "https://flownote-react-staging.vercel.app";
        throw new IllegalArgumentException("Invalid channel");
    }
    static boolean allowedArtifact(String value, String channel, String version) {
        try {
            parts(version); URI uri = URI.create(value);
            return uri.getRawUserInfo() == null && value.equals(origin(channel) + "/downloads/flownote-" + version + ("staging".equals(channel) ? "-staging" : "") + ".apk");
        } catch (IllegalArgumentException error) { return false; }
    }
    static boolean verify(byte[] payload, byte[] signature, byte[] key) throws java.security.GeneralSecurityException {
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(key)));
        verifier.update(payload); return verifier.verify(signature);
    }
}
