package br.com.isageek.bcbridge.maven.fixture;

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

    public static void adviceNoArguments() {
        events += "advice();";
    }

    public static void adviceArguments(String value) {
        events += "advice(" + value + ");";
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
