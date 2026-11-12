package com.flownote.mobile;

import java.util.Locale;
import java.util.Set;

/** Keeps an exact code filename when Android's document provider derives suffixes from MIME. */
final class DownloadExportPolicy {
    private static final Set<String> CODE_EXTENSIONS = Set.of(
        "txt", "text", "log", "csv", "json", "jsonc", "py", "pyi", "pyw", "rs",
        "ts", "mts", "cts", "tsx", "js", "mjs", "cjs", "jsx", "java", "kt", "kts",
        "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "cs", "go", "swift",
        "rb", "rake", "php", "sh", "bash", "zsh", "sql", "yaml", "yml", "toml",
        "ini", "conf", "html", "htm", "css", "scss", "less", "xml", "svg", "md", "markdown"
    );

    private DownloadExportPolicy() { }

    static Set<String> codeExtensions() { return CODE_EXTENSIONS; }

    static String chooserMime(String name, String mime) {
        String selected = mime == null ? "application/octet-stream" : mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (selected.isEmpty()) selected = "application/octet-stream";
        if (!"text/plain".equals(selected) || name == null) return selected;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return selected;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CODE_EXTENSIONS.contains(extension) ? "application/octet-stream" : selected;
    }
}
