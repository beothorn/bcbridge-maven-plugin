package com.github.beothorn.bcbridge.maven.fixture;

public final class RedirectFixture {

    public static String events = "";

    public RedirectFixture() {
    }

    public String original(String value) {
        events += "original(" + value + ");";
        return "original: " + value;
    }

    public String redirected(String value) {
        return "redirected: " + value;
    }

    public static String redirectedWithThis(Object receiver, String value) {
        return receiver.getClass().getSimpleName() + ": " + value;
    }

    public static String redirectedWithArray(Object[] arguments) {
        return "array: " + arguments[0];
    }

    public static void adviceNoArguments() {
        events += "advice();";
    }

    public static void adviceArguments(String value) {
        events += "advice(" + value + ");";
    }

    public static void adviceArray(Object[] arguments) {
        events += "array(" + arguments[0] + ");";
    }

    public static void adviceThis(Object receiver) {
        events += "advice(" + receiver.getClass().getSimpleName() + ");";
    }

    public static void adviceThisAndArguments(Object receiver, String value) {
        events += "advice(" + receiver.getClass().getSimpleName() + "," + value + ");";
    }

    public static String invalidAdviceReturn(String value) {
        return value;
    }
}
