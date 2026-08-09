package br.com.isageek.bcbridge.maven;

final class BridgeConfigurationException extends Exception {

    BridgeConfigurationException(String message) {
        super(message);
    }

    BridgeConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
