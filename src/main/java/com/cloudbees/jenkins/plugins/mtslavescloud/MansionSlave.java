/*
 * The MIT License
 *
 * Copyright 2014 CloudBees.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.FileSystemClan;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import hudson.Extension;
import hudson.Util;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.EphemeralNode;
import hudson.slaves.NodeProperty;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

import javax.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
    private Long createdDate = System.currentTimeMillis();

    /**
     * Keeps track of the last renewal.
     */
    private transient long renewalTimestamp;

    public MansionSlave(VirtualMachineRef vm, SlaveTemplate template, Label label, ComputerLauncher launcher) throws FormException, IOException {
        super(
                massageId(vm),
                "Virtual machine provisioned from "+vm.url,
                template.getMansionType().equals("win") ? "c:\\scratch\\jenkins" : "/scratch/jenkins",
                1,
                Mode.NORMAL,
                label == null ? "" : label.getDisplayName(),
                launcher,
                new MansionRetentionStrategy(),
                Collections.<NodeProperty<?>>emptyList());
        this.vm = vm;
        this.template = template;
    }

    public SlaveTemplate getTemplate() {
        return template;
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

    public static String getSlaveLog(MansionSlave s) {
        if (s==null)    return "(null slave)";
        Computer c = s.toComputer();
        if (c==null)    return "(null computer)";
        try {
            return c.getLog();
        } catch (IOException e) {
            StringWriter w = new StringWriter();
            try {
                w.append('(').append(e.getMessage()).append(")\n");
                PrintWriter pw = new PrintWriter(w);
                try {
                    e.printStackTrace(pw);
                } finally {
                    IOUtils.closeQuietly(pw);
                }
                return w.toString();
            } finally {
                IOUtils.closeQuietly(w);;
            }
        }
    }
    
    public void updateStatus(String status) {
        for (MansionCloud cloud: Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
            for (PlannedMansionSlave p: cloud.getInProgressSet()) {
                if (p.getNode() == this) {
                    p.setStatus(status);
                    return;
                }
            }
        }
    }
    
    public MansionComputer asComputer() {
        return (MansionComputer) toComputer();
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

    /**
     * If we have failed to renew for an extended period of time,
     * the mansion would have disposed the machine, so we should give it up, too.
     */
    private boolean isNotRenewedForTooLong() {
        return System.currentTimeMillis()-renewalTimestamp > TimeUnit2.MINUTES.toMillis(30);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        OUTER:
        for (MansionCloud cloud: Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
            for (PlannedMansionSlave p: cloud.getInProgressSet()) {
                if (p.getNode() == this) {
                    p.onTerminate();
                    break OUTER;
                }
            }
        }
        try {
            FileSystemClan clan = template.getClan();
            clan.update(vm.getState(), createdDate);
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to update the file system clan"));
            LOGGER.log(Level.INFO, "Failed to update the file system clan", e);
        }
        BillingMemoBuilder.BuildHistory history = getNodeProperties().get(BillingMemoBuilder.BuildHistory.class);
        if (history != null) {
            try {
                vm.setMemo(history.toJSONObject());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to set memo "+vm.url, e);
            }
        }

        try {
            vm.dispose();
            listener.getLogger().println("Disposed " + vm.url+" last renewal was "+new Date(renewalTimestamp));
            LOGGER.log(Level.INFO, "Disposed " + vm.url+" last renewal was "+new Date(renewalTimestamp));
        } catch (IOException e) {
            e.printStackTrace(listener.error("Failed to dispose "+vm.url));
            LOGGER.log(Level.INFO, "Failed to dispose "+vm.url, e);
        }
    }

    public void onConnectFailure(String message) {
        for (MansionCloud cloud : Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
            for (PlannedMansionSlave p : cloud.getInProgressSet()) {
                if (p.getNode() == this) {
                    p.onConnectFailure(new IOException(message + "\nLauncher log:\n" + getSlaveLog(this)));
                    return;
                }
            }
        }
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

        //@Override // TODO uncomment once Jenkins 1.551+ 
        protected Level getNormalLoggingLevel() {
            return Level.FINE;
        }

        //@Override // TODO uncomment once Jenkins 1.565+
        protected Level getSlowLoggingLevel() {
            return Level.INFO;
        }

        @Override
        protected void execute(TaskListener listener) throws IOException, InterruptedException {

            String originalThreadName = Thread.currentThread().getName();
            try {
                for (MansionSlave n : filter(jenkins.getNodes(), MansionSlave.class)) {
                    try {
                        Computer c = n.toComputer();
                        if (c != null && (c.isConnecting() || c.isOnline())) {
                            Thread.currentThread().setName(originalThreadName + " - renewing " + n.vm.getId());
                            n.renewLease();
                        } else {
                            LOGGER.log(Level.WARNING, "Not renewing because it appears to be offline: " + n.vm.url);
                        }

                    } catch (IOException e) {
                        e.printStackTrace(listener.error("Failed to renew the lease " + n.vm.url));
                        LOGGER.log(Level.WARNING, "Failed to renew the lease " + n.vm.url, e);

                        if (n.isNotRenewedForTooLong()) {
                            // if we miss the renewal once or twice we can still recover from it,
                            // but if we can't renew for too long, then we do know that the mansion
                            // gets rid of the lease. So at that point, there's no use trying.
                            n.terminate();
                        }
                        // move on to the next one
                    }
                }
                for (MansionCloud c : filter(jenkins.clouds, MansionCloud.class)) {
                    for (PlannedMansionSlave s : c.getInProgressSet()) {
                        if (s.isProvisioning()) {
                            continue;
                        }
                        try {
                            Thread.currentThread().setName(originalThreadName + " - renewing " + s.getVm().getId());
                            s.renewLease();
                        } catch (IOException e) {
                            e.printStackTrace(listener.error("Failed to renew the lease " + s.getVm().url));
                            LOGGER.log(Level.WARNING, "Failed to renew the lease " + s.getVm().url, e);
                            // move on to the next one
                        }
                    }
                    c.getInProgressSet().update();
                }
            } finally {
                Thread.currentThread().setName(originalThreadName);
            }
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MansionSlave.class.getName());
}
