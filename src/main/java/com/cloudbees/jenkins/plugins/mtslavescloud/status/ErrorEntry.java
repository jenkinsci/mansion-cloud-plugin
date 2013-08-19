package com.cloudbees.jenkins.plugins.mtslavescloud.status;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;

/**
 * A cloud which is waiting due to some error
 */
public class ErrorEntry extends Entry {

    public ErrorEntry(MansionCloud cloud) {
        super(cloud);
    }

    public String getLastMessage() {
        return cloud.getLastException() != null ? cloud.getLastException().getMessage()
                : "Unknown error";  // probably a bug

    }

    public String getMessageSummary() {
        if (getLastMessage().length() > 20) {
            return getLastMessage().substring(0, 19) + "...";
        } else {
            return getLastMessage();
        }
    }

    //TODO: wire this to the UI so people can click "Try Now"
    public void doTryNow() {
        cloud.getBackOffCounter().clear();
        //TODO: return to whence they came
    }
}

