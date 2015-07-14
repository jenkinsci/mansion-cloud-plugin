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
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.ComputerListener;
import org.acegisecurity.Authentication;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class MansionComputer extends AbstractCloudComputer<MansionSlave> {
    // MansionSlave, once gets created, is never reconfigured, so we can keep a reference like this.
    private final MansionSlave slave;
    private long creationTime = System.currentTimeMillis();
    private long onlineTime = 0;
    private boolean disconnectInProgress;

    MansionComputer(MansionSlave slave) {
        super(slave);
        this.slave = slave;
    }

    public synchronized boolean isDisconnectInProgress() {
        return disconnectInProgress;
    }

    /*package*/ synchronized void setDisconnectInProgress(boolean disconnectInProgress) {
        this.disconnectInProgress = disconnectInProgress;
    }

    @Override
    public boolean isAcceptingTasks() {
        return !isDisconnectInProgress() && super.isAcceptingTasks();
    }

    /**
     * {@link MansionComputer} is not configurable.
     *
     * This also lets us hide a broken configuration page.
     */
    @Override
    public ACL getACL() {
        final ACL base = super.getACL();
        return new ACL() {
            @Override
            public boolean hasPermission(Authentication a, Permission permission) {
                if (permission== Computer.CONFIGURE)
                    return false;
                return base.hasPermission(a,permission);
            }
        };
    }

    @Override
    protected void kill() {
        // TODO: post 1.510, move this logic to onRemoved() 
        // TODO: investigate if onRemoved() is reliably called for all node removal pathways
        super.kill();
        // the termination involves snapshot and other long running tasks, none of which require the queue lock held
        // so push that work to a separate thread.
        threadPoolForRemoting.submit(new Runnable() {
            public void run() {
                try {
                    slave.terminate();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to terminate " + getDisplayName(), e);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.WARNING, "Failed to terminate " + getDisplayName(), e);
                }
            }
        });
    }

    /**
     * When was this computer created?
     * @return
     */
    public long getCreationTime() {
        return creationTime;
    }

    private static final Logger LOGGER = Logger.getLogger(MansionComputer.class.getName());

    /**
     * When did this computer become idle, considering when it actually came online.
     *
     * This is required because {@link Computer#connectTime} is set when the
     * computer starts connecting, whereas {@link MansionComputer#onlineTime}
     * is set <b>after</b> the connection completes.
     *
     * @return
     */
    public long getIdleStartMillisecondsAfterConnect() {
        return Math.max(getIdleStartMilliseconds(), onlineTime);
    }

    /**
     * Record when connection completes, for more accurate idle detection.
     */
    @Extension
    public static class OnlineListener extends ComputerListener {

        @Override
        public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (c != null && c instanceof MansionComputer)
                ((MansionComputer) c).onlineTime = System.currentTimeMillis();
        }
    }

}
