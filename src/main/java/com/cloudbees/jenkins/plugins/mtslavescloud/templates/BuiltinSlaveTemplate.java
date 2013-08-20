package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;

import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class BuiltinSlaveTemplate extends SlaveTemplate {
    public BuiltinSlaveTemplate(String name) {
        super(name);
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
