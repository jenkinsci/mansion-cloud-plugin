package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.BulkChange;
import hudson.Plugin;
import hudson.init.Initializer;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static hudson.init.InitMilestone.JOB_LOADED;

/**
 * Automatically wires up the {@link MansionCloud} upon initial installation.
 *
 * @author Ryan Campbell
 */
public class MansionPlugin extends Plugin {


    public static final String MANSION_BROKER_URL = System.getProperty(MansionPlugin.class + ".MANSION_BROKER_URL","https://mansion-broker.cloudbees.com/");
    private boolean configured = false;

    @Initializer(after = JOB_LOADED)
    public static void setup() throws IOException {
        Jenkins jenkins = Jenkins.getInstance();
        MansionPlugin thisPlugin = Jenkins.getInstance().getPlugin(MansionPlugin.class);
        thisPlugin.load();
        // only auto-configure mansion once
        if (!thisPlugin.configured) {
            if (jenkins.clouds.get(MansionCloud.class) == null) {
                BulkChange bc = new BulkChange(jenkins);
                try {
                    // add mansion to the top of the list
                    List<Cloud> existing = new ArrayList<Cloud>();
                    jenkins.clouds.addAllTo(existing);
                    jenkins.clouds.clear();
                    jenkins.clouds.add(new MansionCloud(new URL(MANSION_BROKER_URL)));
                    jenkins.clouds.addAll(existing);
                    bc.commit();
                    thisPlugin.configured = true;
                } finally {
                    bc.abort();
                }
            }
            thisPlugin.configured = true;
            thisPlugin.save();
        }
    }


}
