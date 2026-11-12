package com.flownote.mobile;

public final class UpdateSettingsPolicyTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String origin = "https://flownote-react-staging.vercel.app", page = origin + "/settings", target = origin + UpdateSettingsPolicy.PATH;
        check(UpdateSettingsPolicy.mayOpen(origin, page, target, true, true, "GET"));
        check(UpdateSettingsPolicy.mayOpen(origin, page + "?tab=app#updates", target, true, true, "GET"));
        check(!UpdateSettingsPolicy.mayOpen(origin, page, target, false, true, "GET"));
        check(!UpdateSettingsPolicy.mayOpen(origin, page, target, true, false, "GET"));
        check(!UpdateSettingsPolicy.mayOpen(origin, page, target, true, true, "POST"));
        for (String bad : new String[]{"https://evil.example/settings", "http://flownote-react-staging.vercel.app/settings", origin + ":444/settings", "https://user@flownote-react-staging.vercel.app/settings", origin + "/library", origin + "/%73ettings", "data:text/html,settings", "", null})
            check(!UpdateSettingsPolicy.mayOpen(origin, bad, target, true, true, "GET"));
        for (String bad : new String[]{target + "?url=https://evil.example", target + "#install", target + "/", "https://flownote-react.vercel.app" + UpdateSettingsPolicy.PATH, "http://flownote-react-staging.vercel.app" + UpdateSettingsPolicy.PATH, origin + ":444" + UpdateSettingsPolicy.PATH, origin + "/__flownote_native__/%75pdates", null})
            check(!UpdateSettingsPolicy.mayOpen(origin, page, bad, true, true, "GET"));
        System.out.println("PASS update settings: fixed action, HTTPS origin, settings page, main frame, gesture, GET and no parameters");
    }
}
