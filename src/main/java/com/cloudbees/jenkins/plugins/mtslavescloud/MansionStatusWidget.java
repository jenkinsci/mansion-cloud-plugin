package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.Extension;
import hudson.widgets.Widget;

/**
 * Display the Cloud's status so the user knows what's going on.
 */
@Extension
public class MansionStatusWidget extends Widget {
    @Override
    public String getUrlName() {
        return "mansion-status";
    }
}
