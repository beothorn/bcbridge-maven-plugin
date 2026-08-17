package br.com.isageek.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BridgeTest {

    @Test
    void defaultsTypeToRedirect() {
        Bridge bridge = new Bridge();

        assertEquals("redirect", bridge.getType());
        assertEquals(true, bridge.isCaptureArguments());
        assertEquals(false, bridge.isThisAsParameter());
    }

    @Test
    void storesAllPomConfigurationValues() {
        Bridge bridge = new Bridge();
        bridge.setSourceApplication("App1");
        bridge.setSource("com.example.App1Main#foo");
        bridge.setDest("com.example.App2Main#fooWithLog");
        bridge.setType("onMethodEnter");

        assertEquals("App1", bridge.getSourceApplication());
        assertEquals("com.example.App1Main#foo", bridge.getSource());
        assertEquals("com.example.App2Main#fooWithLog", bridge.getDest());
        assertEquals("onMethodEnter", bridge.getType());
    }
}
