package com.github.beothorn.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BridgeTest {

    @Test
    void defaultsTypeToRedirect() {
        Bridge bridge = new Bridge();

        assertEquals("redirect", bridge.getType());
        assertEquals(null, bridge.getCaptureArguments());
        assertEquals(false, bridge.isThisAsParameter());
    }

    @Test
    void storesAllPomConfigurationValues() {
        Bridge bridge = new Bridge();
        bridge.setSource("com.example.App1Main#foo");
        bridge.setDest("com.example.App2Main#fooWithLog");
        bridge.setType("onMethodEnter");

        assertEquals("com.example.App1Main#foo", bridge.getSource());
        assertEquals("com.example.App2Main#fooWithLog", bridge.getDest());
        assertEquals("onMethodEnter", bridge.getType());
    }
}