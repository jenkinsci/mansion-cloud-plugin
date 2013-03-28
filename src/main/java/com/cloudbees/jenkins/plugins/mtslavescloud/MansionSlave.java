package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.mtslaves.client.VirtualMachineRef;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.logging.Logger;

/**
 * {@link Slave} for {@link MansionCloud}
 *
 * @author Kohsuke Kawaguchi
 */
public class MansionSlave extends Slave implements EphemeralNode {
    private final VirtualMachineRef vm;

    public MansionSlave(VirtualMachineRef vm, ComputerLauncher launcher ) throws FormException, IOException {
        super(
                Util.getDigestOf(vm.getId()).substring(0,8),
                "Virtual machine provisioned from "+vm.url,
                "/tmp/"+vm.getId(), // TODO:
                1,
                Mode.NORMAL,
                "", // TODO
                launcher,
                new CloudSlaveRetentionstrategy(),
                Collections.<NodeProperty<?>>emptyList());
        this.vm = vm;

        // suspend retention strategy until we do the initial launch
        this.holdOffLaunchUntilSave = true;
    }

    protected void cancelHoldOff() {
        // resume the retention strategy work that we've suspended in the constructor
        holdOffLaunchUntilSave = false;
    }

    @Override
    public Computer createComputer() {
        return new MansionComputer(this);
    }

    public Node asNode() {
        return this;
    }

    public void terminate() throws IOException, InterruptedException {
        vm.dispose();
        LOGGER.info("Disposed "+vm.url);
    }

    private static final Logger LOGGER = Logger.getLogger(MansionSlave.class.getName());
}
