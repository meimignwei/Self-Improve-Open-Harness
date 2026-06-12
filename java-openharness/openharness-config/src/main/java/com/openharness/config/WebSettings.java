package com.openharness.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbound web tool configuration.
 * Java equivalent of Python's WebSettings Pydantic model.
 */
public class WebSettings {

    private String proxy = null;
    private String resolutionMode = "auto";
    private List<String> syntheticDnsCidrs = new ArrayList<>();

    public String proxy() { return proxy; }
    public void setProxy(String proxy) { this.proxy = proxy; }

    public String resolutionMode() { return resolutionMode; }
    public void setResolutionMode(String resolutionMode) { this.resolutionMode = resolutionMode; }

    public List<String> syntheticDnsCidrs() { return syntheticDnsCidrs; }
    public void setSyntheticDnsCidrs(List<String> syntheticDnsCidrs) { this.syntheticDnsCidrs = syntheticDnsCidrs; }
}
