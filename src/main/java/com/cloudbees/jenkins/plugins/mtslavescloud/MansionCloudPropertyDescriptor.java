package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class MansionCloudPropertyDescriptor extends Descriptor<MansionCloudProperty> {
    /**
     * Returns all the registered {@link MansionCloudPropertyDescriptor}s.
     */
    public static DescriptorExtensionList<MansionCloudProperty,MansionCloudPropertyDescriptor> all() {
        return Jenkins.getInstance().<MansionCloudProperty,MansionCloudPropertyDescriptor>getDescriptorList(MansionCloudProperty.class);
    }
}
