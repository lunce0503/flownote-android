package com.flownote.mobile;

/** Pure JVM checks for ACTION_CREATE_DOCUMENT MIME selection. */
public final class DownloadExportPolicyTest {
    private static void equal(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }

    public static void main(String[] extensions) {
        if (extensions.length == 0) throw new AssertionError("Missing owned code extensions");
        if (!DownloadExportPolicy.codeExtensions().equals(java.util.Set.of(extensions))) {
            throw new AssertionError("Android code extension contract differs from the web owner");
        }
        for (String extension : extensions) {
            equal("application/octet-stream", DownloadExportPolicy.chooserMime("sample." + extension, "text/plain"));
            equal("application/octet-stream", DownloadExportPolicy.chooserMime("sample." + extension.toUpperCase(java.util.Locale.ROOT), "text/plain"));
        }
        equal("application/json", DownloadExportPolicy.chooserMime("sample.json", "application/json"));
        equal("text/plain", DownloadExportPolicy.chooserMime("sample.unknown", "text/plain"));
        equal("text/plain", DownloadExportPolicy.chooserMime(null, "text/plain"));
        equal("application/octet-stream", DownloadExportPolicy.chooserMime("sample.py", null));
        System.out.println("PASS Android code export keeps every owned extension without DocumentsUI MIME suffix rewriting");
    }
}
