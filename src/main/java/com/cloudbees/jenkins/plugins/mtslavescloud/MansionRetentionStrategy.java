package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.dac.storage.Storage;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.slaves.OfflineCause;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MansionRetentionStrategy <T extends MansionComputer> extends CloudSlaveRetentionStrategy<T> {

    @Override
    public long check(T c) {
        long nextCheck = super.check(c);

        if (c.isOffline() && c.getOfflineCause() instanceof OfflineCause.ChannelTermination) {
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
     * For mansion, we may need to rsync if Swarm is loaded. Otherwise, we just remove the slave.
     * @param node
     * @throws IOException
     */
    @Override
    protected void kill(final Node node) throws IOException {
        try {
            //Storage is registered as an extension so we can look it up
            ExtensionList<Storage> extensionList = Jenkins.getInstance().getExtensionList(Storage.class);
            if (extensionList.size() == 0) {
                super.kill(node);
                return;
            }
            final Storage storage = extensionList.get(0);
            final MansionComputer computer = (MansionComputer) node.toComputer();
            computer.setAcceptingTasks(false);

            // perform sync asynchronously so other tasks are not delayed
            Computer.threadPoolForRemoting.submit(new Runnable() {
                public void run() {
                    Slave slave = (Slave) node;
                    try {
                        SSHLauncher launcher = (SSHLauncher) slave.getLauncher();
                        SSHUserPrivateKey key = (SSHUserPrivateKey) launcher.getCredentials();
                        storage.sync(slave, launcher, key);
                    } catch (Throwable t) {
                        LOGGER.log(Level.SEVERE, "Failed to sync slave", t);
                    } finally {

                        // attempt to heuristically detect a race condition
                        // where an executor accepted a task after we checked for
                        // idleness,
                        // but before we marked it as unavailable for tasks
                        if (!computer.isIdle() && computer.isOnline()) {
                            // we lost the race -- mark it as back online
                            computer.setAcceptingTasks(true);
                            return;
                        }
                        for (Executor e : slave.getComputer().getExecutors()) {
                            e.interrupt();
                        }
                        try {
                            MansionRetentionStrategy.super.kill(node);
                        } catch (IOException e) {
                            LOGGER.log(Level.SEVERE, "Error removing slave " + slave.getNodeName());
                        }

                    }
                }
            });
        } catch (NoClassDefFoundError e) {
            super.kill(node);
        }


    }

    /**
     * For Mansion, we want don't want to consider idleness before the computer connects.
     */
    protected boolean isIdleForTooLong(T c) {
        return c.getLaunchedTime() != null && System.currentTimeMillis()-Math.max(c.getIdleStartMilliseconds(),c.getLaunchedTime()) > getIdleMaxTime();
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
