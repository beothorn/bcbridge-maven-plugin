package br.com.isageek.bcbridge.maven.fixture;

public final class RedirectFixture {

    public RedirectFixture() {
    }

    public String original(String value) {
        return "original: " + value;
    }

    public String redirected(String value) {
        return "redirected: " + value;
    }
}
