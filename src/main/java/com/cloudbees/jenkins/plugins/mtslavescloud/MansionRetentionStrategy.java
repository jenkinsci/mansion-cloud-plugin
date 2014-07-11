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

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.slaves.OfflineCause;
import hudson.util.TimeUnit2;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

public class MansionRetentionStrategy <T extends MansionComputer> extends CloudSlaveRetentionStrategy<T> {

    @Override
    public long check(T c) {
        long nextCheck = super.check(c);

        if (c.isOffline() && !c.isConnecting() && c.isAcceptingTasks() && shouldHaveConnectedByNow(c)) {
            LOGGER.fine("Removing "+c.getName()+" because it should have connected by now");
            //take offline without syncing
            try {
                MansionSlave node = c.getNode();
                if (node!=null)    // rare, but n==null if the node is deleted and being checked roughly at the same time
                    super.kill(node);
            } catch (IOException e) {
                LOGGER.warning("Failed to take slave offline: " + c.getName());
            }
        }

        return nextCheck;
    }

    /**
     * For Mansion, we want don't want to consider idleness before the computer connects.
     */
    protected boolean isIdleForTooLong(T c) {
        return (c.isOnline() || shouldHaveConnectedByNow(c))
                && System.currentTimeMillis()-c.getIdleStartMillisecondsAfterConnect() > getIdleMaxTime();
    }

    private boolean shouldHaveConnectedByNow(T c) {
        return System.currentTimeMillis() - c.getCreationTime() > TimeUnit.MINUTES.toMillis(2);
    }

    @Override
    protected void kill(Node n) throws IOException {
        MansionComputer computer = (MansionComputer) n.toComputer();
        // attempt to heuristically detect a race condition
        // where an executor accepted a task after we checked for
        // idleness,
        // but before we marked it as unavailable for tasks
        // See JENKINS-23676
        LOGGER.log(Level.FINE, "Taking node offline since it seems to be idle" + n.getNodeName());
        computer.setAcceptingTasks(false);
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            LOGGER.log(Level.FINE, "Interrupted while sleeping before removing node " + n.getNodeName());
            return;
        }
        if (!computer.isIdle() && computer.isOnline()) {
            LOGGER.log(FINE, computer.getName() + " is no longer idle, aborting termination.");
            // we lost the race -- mark it as back online
            computer.setAcceptingTasks(true);
            return;
        }
        for (Executor e : computer.getExecutors()) {
            e.interrupt();
        }
        LOGGER.log(Level.FINE, "Finally removing node " + n.getNodeName());
        super.kill(n);
    }

    /**
     * If the computer has been idle longer than this time, we'll kill the slave.
     */
    protected long getIdleMaxTime() {
        return TIMEOUT;
    }

    /**
     * How long a slave can be idle before being terminated
     */
    public static long TIMEOUT = Long.getLong(CloudSlaveRetentionStrategy.class.getName()+".timeout", TimeUnit2.SECONDS.toMillis(5));


    private static Logger LOGGER  = Logger.getLogger(MansionRetentionStrategy.class.getName());
}
