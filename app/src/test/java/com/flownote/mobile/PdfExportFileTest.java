package com.flownote.mobile;

public final class PdfExportFileTest {
    public static void main(String[] args) throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("flownote-pdf-test");
        byte[] block = new byte[PdfExportFile.CHUNK];
        System.arraycopy("%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, block, 0, 8);
        var expected = java.security.MessageDigest.getInstance("SHA-256");
        try (var sink = new PdfExportFile(directory.toFile(), PdfExportFile.MAX)) {
            for (long offset = 0; offset < PdfExportFile.MAX; offset += block.length) { sink.append(offset, block); expected.update(block); }
            sink.finish();
            var actual = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.io.FileInputStream(sink.file)) {
                int count; while ((count = input.read(block)) != -1) actual.update(block, 0, count);
            }
            if (!java.util.Arrays.equals(expected.digest(), actual.digest())) throw new AssertionError("Original bytes changed");
        }
        for (long size : new long[]{0, PdfExportFile.MAX + 1}) {
            try { new PdfExportFile(directory.toFile(), size); throw new AssertionError(); } catch (java.io.IOException expectedError) { }
        }
        try (var sink = new PdfExportFile(directory.toFile(), 9)) {
            try { sink.append(1, "%PDF-test".getBytes()); throw new AssertionError(); } catch (java.io.IOException expectedError) { }
            try { sink.append(0, "not a pdf".getBytes()); throw new AssertionError(); } catch (java.io.IOException expectedError) { }
            try { sink.finish(); throw new AssertionError(); } catch (java.io.IOException expectedError) { }
        }
        try (var files = java.nio.file.Files.list(directory)) { if (files.count() != 0) throw new AssertionError("Partial files retained"); }
        java.nio.file.Files.delete(directory);
        System.out.println("PASS PDF 100MiB hash, limits, sequence, header and cancellation cleanup");
    }
}
