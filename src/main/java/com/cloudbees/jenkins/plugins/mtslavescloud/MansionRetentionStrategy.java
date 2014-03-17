package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.slaves.CloudSlaveRetentionStrategy;
import hudson.slaves.OfflineCause;
import hudson.util.TimeUnit2;

import java.io.IOException;
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
