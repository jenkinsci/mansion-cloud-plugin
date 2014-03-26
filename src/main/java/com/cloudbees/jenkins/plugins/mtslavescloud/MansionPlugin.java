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

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplateList;
import hudson.BulkChange;
import hudson.Plugin;
import hudson.init.Initializer;
import hudson.model.RootAction;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static hudson.init.InitMilestone.*;

/**
 * Automatically wires up the {@link MansionCloud} upon initial installation.
 *
 * @author Ryan Campbell
 */
public class MansionPlugin extends Plugin {


    public static final String MANSION_BROKER_URL = System.getProperty(MansionPlugin.class.getName() + ".MANSION_BROKER_URL","https://mansion-broker.cloudbees.com/");
    private boolean configured = false;

    @Initializer(after = JOB_LOADED)
    public static void setup() throws IOException {
        Jenkins jenkins = Jenkins.getInstance();

        // make sure SlaveTemplateList is initialized
        jenkins.getExtensionList(RootAction.class).get(SlaveTemplateList.class);
        MansionPlugin thisPlugin = jenkins.getPlugin(MansionPlugin.class);
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
