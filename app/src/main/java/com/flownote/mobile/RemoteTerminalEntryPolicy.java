package com.flownote.mobile;

import java.net.URI;

final class RemoteTerminalEntryPolicy {
    static final String PATH = "/__flownote_native__/remote-terminal";

    static boolean mayOpen(String origin, String current, String target, boolean mainFrame, boolean gesture, String method) {
        if (!mainFrame || !gesture || !"GET".equals(method) || !(origin + PATH).equals(target)) return false;
        try {
            URI base = URI.create(origin), page = URI.create(current);
            return "https".equals(base.getScheme()) && "https".equals(page.getScheme())
                && base.getHost().equals(page.getHost()) && page.getRawUserInfo() == null
                && (page.getPort() == -1 || page.getPort() == 443);
        } catch (IllegalArgumentException | NullPointerException error) {
            return false;
        }
    }
}
