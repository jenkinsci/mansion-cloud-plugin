package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.mtslaves.client.VirtualMachineRef;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;

import java.io.IOException;
import java.util.Collections;

/**
 * {@link Slave} for {@link MansionCloud}
 *
 * @author Kohsuke Kawaguchi
 */
public class MansionSlave extends Slave implements EphemeralNode {
    public MansionSlave(VirtualMachineRef vm, ComputerLauncher launcher ) throws FormException, IOException {
        super(vm.getId(), "Virtual machine provisioned from "+vm.url,
                "/tmp/"+vm.getId(), // TODO:
                1,
                Mode.NORMAL,
                "", // TODO
                launcher,
                RetentionStrategy.INSTANCE,
                Collections.<NodeProperty<?>>emptyList());
    }

    public Node asNode() {
        return this;
    }
}
