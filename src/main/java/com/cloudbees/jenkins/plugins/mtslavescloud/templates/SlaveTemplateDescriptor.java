package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
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

    /**
     * Do we have multiple clouds configured that requires templates to distinguish them?
     */
    public boolean hasMultipleAccounts() {
        return Jenkins.getInstance().clouds.getAll(MansionCloud.class).size()>1;
    }
}
