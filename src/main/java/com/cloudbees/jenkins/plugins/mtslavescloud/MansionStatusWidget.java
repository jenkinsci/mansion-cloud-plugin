package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.Extension;
import hudson.widgets.Widget;
import jenkins.model.Jenkins;

/**
 * Display the Cloud's status so the user knows what's going on.
 */
@Extension
public class MansionStatusWidget extends Widget {
    @Override
    public String getUrlName() {
        return "mansion-status";
    }

    public String getWidgetUrl() {
        int idx=0;
        for (Widget w : Jenkins.getInstance().getWidgets()) {
            if (w==this) {
                return "widgets/"+idx;
            }
            idx++;
        }
        throw new AssertionError();
    }
}
