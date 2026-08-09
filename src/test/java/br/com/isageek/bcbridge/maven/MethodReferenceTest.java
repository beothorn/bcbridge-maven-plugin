package br.com.isageek.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MethodReferenceTest {

    @Test
    void parsesClassAndMethod() throws Exception {
        MethodReference reference = MethodReference.parse("com.example.App#print", "source");

        assertEquals("com.example.App", reference.className());
        assertEquals("print", reference.methodName());
    }

    @Test
    void rejectsMalformedReferences() {
        assertThrows(BridgeConfigurationException.class,
                () -> MethodReference.parse("com.example.App", "source"));
        assertThrows(BridgeConfigurationException.class,
                () -> MethodReference.parse("#print", "source"));
        assertThrows(BridgeConfigurationException.class,
                () -> MethodReference.parse("com.example.App#", "source"));
    }
}
