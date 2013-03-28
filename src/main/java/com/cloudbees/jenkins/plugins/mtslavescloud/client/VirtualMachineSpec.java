package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import net.sf.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
public class VirtualMachineSpec {
    /**
     * Configuration fragments.
     *
     * It can contain {@link JSONObject}s or other beans databound by json-lib.
     */
    public List<Object> configs = new ArrayList<Object>();
}
