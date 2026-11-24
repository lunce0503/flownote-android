package com.flownote.mobile;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteTerminalEntryPolicyTest {
    private static final String ORIGIN = "https://fot.example";
    private static final String PAGE = ORIGIN + "/planner";
    private static final String TARGET = ORIGIN + RemoteTerminalEntryPolicy.PATH;

    @Test public void fixedLinkOpensFromTheTrustedAppMenu() {
        assertTrue(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, PAGE, TARGET, true, true, "GET"));
    }

    @Test public void rejectsUntrustedPagesAndTargets() {
        for (String page : new String[] { null, "https://other.example/planner", "http://fot.example", ORIGIN + ":444", "https://user@fot.example" }) {
            assertFalse(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, page, TARGET, true, true, "GET"));
        }
        for (String target : new String[] { TARGET + "?host=other", TARGET + "#terminal", ORIGIN + "/__flownote_native__/updates" }) {
            assertFalse(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, PAGE, target, true, true, "GET"));
        }
    }

    @Test public void requiresAnExplicitMainFrameGet() {
        assertFalse(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, PAGE, TARGET, true, false, "GET"));
        assertFalse(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, PAGE, TARGET, false, true, "GET"));
        assertFalse(RemoteTerminalEntryPolicy.mayOpen(ORIGIN, PAGE, TARGET, true, true, "POST"));
    }
}
