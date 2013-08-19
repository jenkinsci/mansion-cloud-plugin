package com.cloudbees.jenkins.plugins.mtslavescloud.status;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;

/**
 * A cloud which is provisioning
 */
public class ProvisioningEntry extends Entry {

    public ProvisioningEntry(MansionCloud cloud) {
        super(cloud);
    }

    public int getNumInProgress() {
        return cloud.getProvisioningsInProgress();
    }
}
