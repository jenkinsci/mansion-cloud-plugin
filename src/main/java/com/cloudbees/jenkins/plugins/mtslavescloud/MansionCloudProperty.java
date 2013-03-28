package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.ExtensionPoint;
import hudson.model.AbstractDescribableImpl;

/**
 * Extensible property of {@link MansionCloud}.
 *
 * <p>
 * This is normally used to maintain some configuration so that {@link MansionVmConfigurator}
 * can request a proper VM.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MansionCloudProperty extends AbstractDescribableImpl<MansionCloudProperty> implements ExtensionPoint {
    // descriptor must be of the MansionCloudPropertyDescriptor type
    public MansionCloudPropertyDescriptor getDescriptor() {
        return (MansionCloudPropertyDescriptor) super.getDescriptor();
    }
}
