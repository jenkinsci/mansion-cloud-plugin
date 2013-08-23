package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.FileSystemClan;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import hudson.Extension;
import hudson.Util;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.filter;

/**
 * {@link Slave} for {@link MansionCloud}
 *
 * @author Kohsuke Kawaguchi
 */
public class MansionSlave extends AbstractCloudSlave implements EphemeralNode {
    public static final int LEASE_RENEWAL_PERIOD_SECONDS = Integer.getInteger(MansionSlave.class.getName() + ".LEASE_RENEWAL_PERIOD_SECONDS", 30);
    private final VirtualMachineRef vm;
    private final SlaveTemplate template;

    /**
     * Keeps track of the last renewal.
     */
    private transient long renewalTimestamp;

    public MansionSlave(VirtualMachineRef vm, SlaveTemplate template, Label label, ComputerLauncher launcher) throws FormException, IOException {
        super(
                massageId(vm),
                "Virtual machine provisioned from "+vm.url,
                "/scratch/jenkins", // TODO:
                1,
                Mode.NORMAL,
                label == null ? "" : label.getDisplayName(),
                launcher,
                new MansionRetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList());
        this.vm = vm;
        this.template = template;

        // suspend retention strategy until we do the initial launch
        this.holdOffLaunchUntilSave = true;
    }


    /**
     * Compute ID from {@link VirtualMachineRef#getId()}.
     *
     * If we can, we'd like to use the ID as-is to assist diagnostics, but if it contains
     * a problematic character or too long, compute our own ID.
     */
    private static String massageId(VirtualMachineRef vm) {
        String id = vm.getId();
        if (id.contains("/") || id.length()>8)
            return Util.getDigestOf(id).substring(0,8);
        else
            return id;
    }

    protected void cancelHoldOff() {
        // resume the retention strategy work that we've suspended in the constructor
        holdOffLaunchUntilSave = false;
    }

    @Override
    public MansionComputer createComputer() {
        return new MansionComputer(this);
    }

    public Node asNode() {
        return this;
    }

    private void renewLease() throws IOException {
        vm.renew();
        LOGGER.fine("Renewed a lease of " + vm.url);
        renewalTimestamp = System.currentTimeMillis();
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        FileSystemClan clan = template.getClan();
        clan.update(vm.getState());
        vm.dispose();
        listener.getLogger().println("Disposed " + vm.url+" last renewal was "+new Date(renewalTimestamp));
        LOGGER.log(Level.INFO, "Disposed " + vm.url+" last renewal was "+new Date(renewalTimestamp));
    }

    @Extension
    public static class MansionLeaseRenewal extends AsyncPeriodicWork {

        @Inject
        Jenkins jenkins;

        public MansionLeaseRenewal() {
            super("Mansion Lease Renewal");
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.SECONDS.toMillis(LEASE_RENEWAL_PERIOD_SECONDS);
        }

        @Override
        protected void execute(TaskListener listener) throws IOException, InterruptedException {
            for (MansionSlave n : filter(jenkins.getNodes(), MansionSlave.class)) {
                try {
                    n.renewLease();
                } catch (IOException e) {
                    e.printStackTrace(listener.error("Failed to renew the lease " + n.vm.url));
                    LOGGER.log(Level.WARNING, "Failed to renew the lease " + n.vm.url, e);
                    // move on to the next one
                }
            }
            for (MansionCloud c : filter(jenkins.clouds, MansionCloud.class)) {
                for (PlannedMansionSlave s : c.getInProgressSet()) {
                    try {
                        s.renewLease();
                    } catch (IOException e) {
                        e.printStackTrace(listener.error("Failed to renew the lease " + s.getVm().url));
                        LOGGER.log(Level.WARNING, "Failed to renew the lease " + s.getVm().url, e);
                        // move on to the next one
                    }
                }
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MansionSlave.class.getName());
}
