package br.com.isageek.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

class BCBridgeExecuteTest {

    @Test
    void reportsWhenNoBridgesAreConfigured() throws Exception {
        BCBridgeExecute mojo = new BCBridgeExecute();
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);

        mojo.execute();

        assertEquals(List.of("No bridges configured"), log.infoMessages);
    }

    @Test
    void rejectsUnsupportedBridgeTypes() {
        BCBridgeExecute mojo = new BCBridgeExecute();
        RecordingLog log = new RecordingLog();
        mojo.setLog(log);
        mojo.setBridges(List.of(bridge("com.example.App1Main#foo", "com.example.App2Main#fooWithLog", "onMethodEnter")));

        Exception error = assertThrows(Exception.class, mojo::execute);

        assertEquals("Unsupported bridge type 'onMethodEnter'. Supported types are 'redirect', 'OnMethodEnter', and 'OnMethodExit'.",
                error.getMessage());
    }

    private static Bridge bridge(String source, String dest, String type) {
        Bridge bridge = new Bridge();
        bridge.setSource(source);
        bridge.setDest(dest);
        if (type != null) {
            bridge.setType(type);
        }
        return bridge;
    }

    private static final class RecordingLog implements Log {
        private final List<String> infoMessages = new ArrayList<>();

        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence content) { }
        @Override public void debug(CharSequence content, Throwable error) { }
        @Override public void debug(Throwable error) { }
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence content) { infoMessages.add(content.toString()); }
        @Override public void info(CharSequence content, Throwable error) { info(content); }
        @Override public void info(Throwable error) { info(error.toString()); }
        @Override public boolean isWarnEnabled() { return false; }
        @Override public void warn(CharSequence content) { }
        @Override public void warn(CharSequence content, Throwable error) { }
        @Override public void warn(Throwable error) { }
        @Override public boolean isErrorEnabled() { return false; }
        @Override public void error(CharSequence content) { }
        @Override public void error(CharSequence content, Throwable error) { }
        @Override public void error(Throwable error) { }
    }
}
