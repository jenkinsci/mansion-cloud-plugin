package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;

/**
 * @author Kohsuke Kawaguchi
 */
public class LocalSlaveTemplate extends SlaveTemplate {
    private String definition;


    public LocalSlaveTemplate(String name) {
        super(name);
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
