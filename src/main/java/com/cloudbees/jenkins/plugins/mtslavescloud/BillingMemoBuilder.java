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

import hudson.Extension;
import hudson.matrix.MatrixBuild;
import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.model.Node;
import hudson.model.listeners.RunListener;
import hudson.slaves.NodeProperty;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the billing memo posted to the virtual machine before termination
 * which includes a JSON array with details of each build which was
 * run on that VM.
 *
 * This avoids global searches for all builds for a given node
 * and will work even if the node's computer is offline.
 *
 */
@Extension
public class BillingMemoBuilder extends RunListener<AbstractBuild> {

    @Override
    public void onFinalized(AbstractBuild run) {
        Node node = run.getBuiltOn();
        if (node instanceof MansionSlave) {
            BuildHistory history = node.getNodeProperties().get(BuildHistory.class);
            if (history == null) {
                history = new BuildHistory();
            }
            history.add(run);
            try {
                node.getNodeProperties().replace(history);
            } catch (IOException x) {
                Logger.getLogger(BillingMemoBuilder.class.getName()).log(Level.WARNING, null, x);
            }
        }
    }

    /**
     * Stores a JSON representation of the build history of this node.
     */
    public static class BuildHistory extends NodeProperty<MansionSlave> {
        private JSONArray builds = new JSONArray();

        public void add(AbstractBuild run) {
            String n = run.getClass().getName();
            if (!n.equals("hudson.maven.MavenBuild") &&  ! (run instanceof MatrixBuild)) {
                JSONObject build = new JSONObject();
                build.element("url", run.getUrl());
                build.element("durationInMilliseconds", run.getDuration());
                build.element("project", run.getParent().getName());
                build.element("number", run.number);
                build.element("scheduledTimestamp", run.getTimeInMillis());

                JSONArray users = new JSONArray();

                // not sure why this is showing up as an object
                for (Object o : run.getCauses()) {
                    if (o instanceof Cause.UserIdCause) {
                        Cause.UserIdCause u = (Cause.UserIdCause) o;
                        users.add(u.getUserId() == null ? "(none)" : u.getUserId());
                    }
                }
                build.element("userCauses", users);

                builds.add(build);
            }

        }
        public JSONObject toJSONObject() {
            if (builds.size() == 0) {
                return new JSONObject();
            } else {
                return new JSONObject().element("builds",builds);
            }
        }
    }

}
