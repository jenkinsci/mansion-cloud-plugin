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
import hudson.model.Queue;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

public class MansionRetentionStrategy <T extends MansionComputer> extends CloudSlaveRetentionStrategy<T> {

    private transient volatile boolean disconnectInProgress;
    
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
    protected void kill(final Node n) throws IOException {       
        final MansionComputer computer = (MansionComputer) n.toComputer();
        
        final String nodeName = n.getNodeName();
        LOGGER.log(Level.FINE, "Taking node {0} offline since it seems to be idle", n.getNodeName());
        // we need to have a private block as other threads could try to turn on accepting tasks
        computer.setDisconnectInProgress(true);
        // set accepting tasks so that others can see this flag if expecting it
        computer.setAcceptingTasks(false);
        synchronized (this) {
            if (disconnectInProgress) {
                // if we already have a background thread running, then we can return immediately
                return;
            }
            disconnectInProgress = true;
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            public void run() {
                // TODO once Jenkins 1.607+ we can remove the sleep as the withLock will give atomic guarantee

                // attempt to heuristically detect a race condition
                // where an executor accepted a task after we checked for
                // idleness,
                // but before we marked it as unavailable for tasks
                // See JENKINS-23676
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.FINE, "Interrupted while sleeping before removing node {0}", nodeName);
                    return;
                }
                
                queueLock.withLock(new Runnable() {
                    public void run() {
                        if (!computer.isIdle() && computer.isOnline()) {
                            LOGGER.log(FINE, computer.getName() + " is no longer idle, aborting termination.");
                            // we lost the race -- mark it as back online
                            computer.setAcceptingTasks(true);
                            computer.setDisconnectInProgress(false);
                            synchronized (MansionRetentionStrategy.this) {
                                disconnectInProgress = false;
                            }
                            return;
                        }
                        // TODO figure out why this cannot just be computer.getNode().terminate()
                        try {
                            for (Executor e : computer.getExecutors()) {
                                e.interrupt();
                            }
                            LOGGER.log(Level.FINE, "Finally removing node " + n.getNodeName());
                            MansionRetentionStrategy.super.kill(n);
                        } catch (IOException e) {
                            computer.setAcceptingTasks(true);
                            computer.setDisconnectInProgress(false);
                            synchronized (MansionRetentionStrategy.this) {
                                disconnectInProgress = false;
                            }
                        }
                    }
                });
            }
        });
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
    
    private static final QueueLock queueLock = newQueueLock();
    
    private static QueueLock newQueueLock() {
        try {
            return new ReflectionPost592QueueLock();
        } catch (NoSuchMethodException e) {
            return new Pre592QueueLock();
        }
    }

    // TODO replace all this with Queue.withLock once Jenkins 1.592+
    interface QueueLock {
        void withLock(Runnable runnable);
    }
    
    private static class Pre592QueueLock implements QueueLock {
        @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
        public void withLock(Runnable runnable) {
            final Jenkins jenkins = Jenkins.getInstance();
            final Object monitor = jenkins == null ? getClass() : jenkins.getQueue();
            synchronized (monitor) {
                runnable.run();
            }
        }
    }
    
    private static class ReflectionPost592QueueLock implements QueueLock {
        private final Method withLock;

        private ReflectionPost592QueueLock() throws NoSuchMethodException {
            this.withLock = Queue.class.getMethod("withLock", Runnable.class);
            if (!Modifier.isStatic(withLock.getModifiers())) {
                throw new NoSuchMethodException("Expecting withLock(Runnable) to be static");
            }
            if (!Modifier.isPublic(withLock.getModifiers())) {
                throw new NoSuchMethodException("Expecting withLock(Runnable) to be static");
            }
        }

        public void withLock(Runnable runnable) {
            try {
                withLock.invoke(null, runnable);
            } catch (IllegalAccessException e) {
                // fall back, but should never get here as the constructor will blow up first
                new Pre592QueueLock().withLock(runnable);
            } catch (InvocationTargetException e) {
                // ignore
            }
        }
    }
}
