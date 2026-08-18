package com.github.beothorn.bcbridge.maven;

record MethodReference(String className, String methodName) {

    static MethodReference parse(String value, String fieldName) throws BridgeConfigurationException {
        if (value == null || value.isBlank()) {
            throw new BridgeConfigurationException("Bridge " + fieldName + " must not be empty");
        }

        int separator = value.indexOf('#');
        if (separator <= 0 || separator != value.lastIndexOf('#') || separator == value.length() - 1) {
            throw new BridgeConfigurationException(
                    "Invalid bridge " + fieldName + " '" + value + "'; expected fully.qualified.Class#method");
        }

        return new MethodReference(value.substring(0, separator), value.substring(separator + 1));
    }
}
