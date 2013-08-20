package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class SlaveTemplateDescriptor extends Descriptor<SlaveTemplate> {

    public static DescriptorExtensionList<SlaveTemplate,SlaveTemplateDescriptor> all() {
        return Jenkins.getInstance().getDescriptorList(SlaveTemplate.class);
    }

    /**
     * Creates a new {@link SlaveTemplate}.
     */
    public abstract SlaveTemplate newInstance(String name);
}
