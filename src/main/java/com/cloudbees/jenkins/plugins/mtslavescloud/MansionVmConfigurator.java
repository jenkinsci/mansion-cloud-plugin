package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Label;
import jenkins.model.Jenkins;

import java.io.IOException;

/**
 * Extension point for constructing a request for a virtual machine.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MansionVmConfigurator implements ExtensionPoint {
    // TODO: add template currently being provisioned
    public abstract void configure(MansionCloud mansion, Label label, VirtualMachineSpec spec) throws IOException, InterruptedException;

    public static ExtensionList<MansionVmConfigurator> all() {
        return Jenkins.getInstance().getExtensionList(MansionVmConfigurator.class);
    }
}
