package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor.FormException;
import net.sf.json.JSONObject;

import javax.servlet.ServletException;

/**
 * {@link SlaveTemplate} that lets the user defines the spec JSON. Experimental.
 *
 * @author Kohsuke Kawaguchi
 */
public class LocalSlaveTemplate extends SlaveTemplate {
    private String mansionType;
    private String definition;

    public LocalSlaveTemplate(String displayName) {
        super(displayName);
    }

    public String getDefinition() {
        return definition;
    }

    @Override
    public JSONObject createSpec() {
        return JSONObject.fromObject(getDefinition());
    }

    @Override
    public String getMansionType() {
        return mansionType;
    }

    @Override
    protected void submit(JSONObject json) throws ServletException, FormException {
        super.submit(json);
        definition = json.getString("definition");
        mansionType = json.getString("mansionType");
    }

    @Extension
    public static class DescriptorImpl extends SlaveTemplateDescriptor {
        @Override
        public LocalSlaveTemplate newInstance(String name) {
            return new LocalSlaveTemplate(name);
        }

        @Override
        public String getDisplayName() {
            return "Local Slave Template";
        }
    }
}
