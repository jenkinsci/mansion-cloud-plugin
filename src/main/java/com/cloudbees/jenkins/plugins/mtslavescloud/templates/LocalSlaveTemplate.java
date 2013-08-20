package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;

/**
 * @author Kohsuke Kawaguchi
 */
public class LocalSlaveTemplate extends SlaveTemplate {
    private String definition;

    public LocalSlaveTemplate(String name) {
        super(name);
    }

    @Override
    protected void submit(StaplerRequest request, JSONObject json) throws ServletException, FormException {
        definition = json.getString("definition");
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
