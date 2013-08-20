package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;
import hudson.model.Descriptor.FormException;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class BuiltinSlaveTemplate extends SlaveTemplate {
    public BuiltinSlaveTemplate(String name) {
        super(name);
    }

    @Override
    protected void submit(StaplerRequest request, JSONObject json) throws ServletException, FormException {
        // nothing to configure
    }

    @Override
    public synchronized void save() throws IOException {
        // no-op as there's nothing to save
    }

    @Extension
    public static class DescriptorImpl extends SlaveTemplateDescriptor {
        @Override
        public BuiltinSlaveTemplate newInstance(String name) {
            // can't manually create this guy
            throw new UnsupportedOperationException();
        }

        @Override
        public String getDisplayName() {
            return "Built-in Slave Template";
        }
    }
}
