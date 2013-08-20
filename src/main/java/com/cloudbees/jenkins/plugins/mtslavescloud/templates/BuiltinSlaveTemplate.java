package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;

/**
 * @author Kohsuke Kawaguchi
 */
public class BuiltinSlaveTemplate extends SlaveTemplate {
    public BuiltinSlaveTemplate(String name) {
        super(name);
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
