package com.cloudbees.jenkins.plugins.mtslavescloud.status;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import hudson.Extension;
import hudson.model.Hudson;
import hudson.widgets.Widget;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Display the Cloud's status so the user knows what's going on.
 */
@Extension
public class MansionStatusWidget extends Widget{


    public boolean isDisplayed() {
        return getEntries().iterator().hasNext();
    }

    @Override
    public String getUrlName() {
        return "mansion-status";
    }

    public String getWidgetUrl() {
        List<Widget> list = Hudson.getInstance().getWidgets();
        Iterator<Widget> iterator = list.iterator();
        for (int i = 0; i < list.size() && iterator.hasNext(); i++) {
            Widget widget = iterator.next();
            if (widget == this) {
                return "/widgets/" + i;
            }
        }
        return null;
    }

    /**
     * We should only display Clouds which are provisioning
     * or are in some kind of error state.
     *
     * @return
     */
    public Iterable<Entry> getEntries() {
        List<Entry> stats = new ArrayList<Entry>();
        for (MansionCloud cloud : Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
            if (cloud.getBackOffCounter().shouldBackOff() || cloud.getProvisioningsInProgress() > 0) {
                stats.add(Entry.entryFor(cloud));
            }
        }
        return stats;
    }
}
