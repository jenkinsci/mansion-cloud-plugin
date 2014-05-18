package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplateList;
import com.cloudbees.mtslaves.client.HardwareSpec;
import hudson.Extension;
import hudson.model.Label;
import hudson.model.queue.CauseOfBlockage;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;

/**
 * Prevent invocation of {@link MansionCloud#provision(hudson.model.Label, int)}
 * when there are quota problems.
 *
 * @author  Ryan Campbell
 */
@Extension
public class QuotaProblemProvsioningBlocker extends CloudProvisioningListener {

    @Override
    public CauseOfBlockage canProvision(Cloud cloud, Label label, int numExecutors) {
        if (cloud instanceof MansionCloud) {
            MansionCloud mc = (MansionCloud) cloud;
            SlaveTemplate st = SlaveTemplateList.get().get(label);
            HardwareSpec box = mc.getBoxOf(st, label);

            if (mc.getQuotaProblems().isBlocked(box, st)) {
                return new CauseOfBlockage() {
                    @Override
                    public String getShortDescription() {
                        return "CloudBees Cloud Slaves cannot provision slaves due to quota problems";
                    }
                };
            }
        }
        return null;
    }
}
