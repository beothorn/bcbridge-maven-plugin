package br.com.isageek.bcbridge.maven;

/** Configuration for one method bridge. */
public final class Bridge {

    private String sourceApplication;
    /** JavaFlame matcher expression selecting source classes and methods. */
    private String source;
    private String dest;
    private String type = "redirect";
    private String captureArguments;
    private boolean thisAsParameter;

    public String getSourceApplication() {
        return sourceApplication;
    }

    public void setSourceApplication(String sourceApplication) {
        this.sourceApplication = sourceApplication;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDest() {
        return dest;
    }

    public void setDest(String dest) {
        this.dest = dest;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCaptureArguments() {
        return captureArguments;
    }

    public void setCaptureArguments(String captureArguments) {
        this.captureArguments = captureArguments;
    }

    public boolean isThisAsParameter() {
        return thisAsParameter;
    }

    public void setThisAsParameter(boolean thisAsParameter) {
        this.thisAsParameter = thisAsParameter;
    }
}
