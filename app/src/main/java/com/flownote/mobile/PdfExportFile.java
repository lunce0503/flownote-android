package com.flownote.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** A bounded private-cache sink. It never reads an entire PDF into memory. */
final class PdfExportFile implements AutoCloseable {
    static final int CHUNK = 256 * 1024;
    static final long MAX = 100L * 1024 * 1024;
    final File file;
    final long size;
    private FileOutputStream output;
    private long written;
    PdfExportFile(File cache, long size) throws IOException {
        if (size < 5 || size > MAX) throw new IOException("PDF size rejected");
        this.size = size;
        file = File.createTempFile("flownote-pdf-", ".pdf", cache);
        output = new FileOutputStream(file);
    }
    synchronized void append(long offset, byte[] bytes) throws IOException {
        if (output == null || offset != written || bytes.length != Math.min(CHUNK, size - written)) throw new IOException("PDF sequence rejected");
        if (written == 0 && !(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-')) throw new IOException("PDF header rejected");
        output.write(bytes); written += bytes.length;
    }
    synchronized void finish() throws IOException {
        if (written != size) throw new IOException("Incomplete PDF");
        if (output != null) { output.close(); output = null; }
    }
    @Override public synchronized void close() {
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        output = null;
        file.delete();
    }
}
