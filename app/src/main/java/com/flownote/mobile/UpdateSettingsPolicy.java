package com.flownote.mobile;

import java.net.URI;

/** A user-initiated, main-frame settings action; never a general-purpose web bridge. */
final class UpdateSettingsPolicy {
    static final String PATH = "/__flownote_native__/updates";

    static boolean mayOpen(String origin, String current, String target, boolean mainFrame, boolean gesture, String method) {
        if (!mainFrame || !gesture || !"GET".equals(method)) return false;
        try {
            URI base = URI.create(origin), page = URI.create(current);
            return target.equals(origin + PATH) && "https".equals(base.getScheme())
                && "https".equals(page.getScheme()) && base.getHost().equals(page.getHost())
                && page.getRawUserInfo() == null && (page.getPort() == -1 || page.getPort() == 443)
                && "/settings".equals(page.getRawPath());
        } catch (IllegalArgumentException | NullPointerException error) { return false; }
    }
}
