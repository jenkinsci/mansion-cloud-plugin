package com.cloudbees.jenkins.plugins.mtslavescloud.status;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;

/**
 * A status entry for the Mansion Status Widget
 */
public abstract class Entry {

    protected final MansionCloud cloud;

    public Entry(MansionCloud cloud) {
        this.cloud = cloud;
    }

    public MansionCloud getCloud() {
        return cloud;
    }

    public static Entry entryFor(MansionCloud c) {
        if (c.getProvisioningsInProgress() > 0) {
            return new ProvisioningEntry(c);
        } else {
            return new ErrorEntry(c);
        }
    }

}
