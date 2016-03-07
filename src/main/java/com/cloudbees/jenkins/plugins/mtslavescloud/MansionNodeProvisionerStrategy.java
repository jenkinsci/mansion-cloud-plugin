/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
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

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplateList;
import com.cloudbees.mtslaves.client.HardwareSpec;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bypass the default NodeProvisioner to create slave as soon as a build start. This
 * removes the need to hack hudson.model.LoadStatistics.clock property to have slaves in a resonable time.
 */
@Extension
public class MansionNodeProvisionerStrategy extends NodeProvisioner.Strategy {
    private static final Logger LOGGER = Logger.getLogger(MansionNodeProvisionerStrategy.class.getName());

    private volatile boolean enabled =
            Boolean.parseBoolean(
                    System.getProperty(MansionNodeProvisionerStrategy.class.getName()+".enabled", "true")
            );

    /**
     * Returns the strategy singleton for the current Jenkins instance.
     *
     * @return the strategy singleton for the current Jenkins instance or {@code null}
     */
    @CheckForNull
    public static MansionNodeProvisionerStrategy getInstance() {
        return ExtensionList.lookup(NodeProvisioner.Strategy.class).get(MansionNodeProvisionerStrategy.class);
    }

    /**
     * Returns {@code true} if this strategy is enabled for the current Jenkins instance.
     *
     * @return {@code true} if this strategy is enabled for the current Jenkins instance.
     */
    public static boolean isEnabled() {
        MansionNodeProvisionerStrategy strategy = getInstance();
        return strategy != null && strategy.enabled;
    }

    /**
     * Sets whether this strategy is enabled for the current Jenkins instance. Useful to disable this strategy
     * in groovy console
     *
     * @param enabled if {@code true} then the strategy will be enabled - including injecting it in the list of
     *                strategies if necessary, if {@code false} then the strategy will be disabled.
     */
    public static void setEnabled(boolean enabled) {
        MansionNodeProvisionerStrategy strategy = getInstance();
        if (strategy == null && enabled) {
            strategy = new MansionNodeProvisionerStrategy();
            ExtensionList.lookup(NodeProvisioner.Strategy.class).add(0, strategy);
        }
        if (strategy != null) {
            strategy.enabled = enabled;
        }
    }

    @Nonnull
    @Override
    public NodeProvisioner.StrategyDecision apply(@Nonnull NodeProvisioner.StrategyState strategyState) {
        final Label label = strategyState.getLabel();

        final SlaveTemplate st = SlaveTemplateList.get().get(label);
        if (st == null) {
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        if (!st.isEnabled()) {
            LOGGER.log(Level.FINE, "Slave template is disabled {0}", st);
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        final MansionCloud mansionCloud = getCloudImpl();
        if (mansionCloud == null) {
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        if (mansionCloud.getBackOffCounter(st).isBackOffInEffect()) {
            LOGGER.log(Level.FINE, "Back off in effect for {0}", st);
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        final HardwareSpec box = mansionCloud.getBoxOf(st, label);
        if (mansionCloud.getQuotaProblems().isBlocked(box, st)) {
            LOGGER.log(Level.FINE, "Provisioning of {0} blocked by quota problems.", st);
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
        int availableCapacity = snapshot.getAvailableExecutors() + snapshot.getConnectingExecutors() +
                strategyState.getAdditionalPlannedCapacity();
        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(Level.FINE, "Available capacity={0}, currentDemand={1}",
                new Object[]{availableCapacity, currentDemand});
        if (availableCapacity < currentDemand) {
            Collection<NodeProvisioner.PlannedNode> plannedNodes = mansionCloud.provision(label, currentDemand - availableCapacity);
            LOGGER.log(Level.FINE, "Planned {0} new nodes", plannedNodes.size());
            strategyState.recordPendingLaunches(plannedNodes);
            availableCapacity += plannedNodes.size();
            LOGGER.log(Level.FINE, "After provisioning, available capacity={0}, currentDemand={1}",
                    new Object[]{availableCapacity, currentDemand});
        }
        if (availableCapacity >= currentDemand) {
            LOGGER.log(Level.FINE, "Provisioning completed");
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(Level.FINE, "Provisioning not complete, consulting remaining strategies");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
    }

    /**
     * Gets the {@link MansionCloud} instance.
     * @return the {@link MansionCloud} instance or {@code null}.
     */
    @CheckForNull
    private static MansionCloud getCloudImpl() {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return null;
        }
        MansionCloud mansionCloud = null;
        for (Cloud cloud : jenkins.clouds) {
            if (cloud instanceof MansionCloud) {
                mansionCloud = (MansionCloud) cloud;
                break;
            }
        }
        return mansionCloud;
    }
}
