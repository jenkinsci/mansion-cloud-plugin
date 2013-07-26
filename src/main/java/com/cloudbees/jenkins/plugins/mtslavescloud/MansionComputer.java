package com.cloudbees.jenkins.plugins.mtslavescloud;

import edu.umd.cs.findbugs.annotations.CheckForNull;
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

    /**
     * When the computer finished connecting. Milliseconds since epoch.
     */
    private Long launchedTime;

    MansionComputer(MansionSlave slave) {
        super(slave);
        this.slave = slave;
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

    /**
     * When was this computer fully launched?
     */
    public @CheckForNull Long getLaunchedTime() {
        return launchedTime;
    }

    // TODO: post 1.510, move this logic to onRemoved()
    @Override
    protected void kill() {
        try {
            super.kill();
            slave.terminate();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate "+getDisplayName());
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate " + getDisplayName());
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MansionComputer.class.getName());

    @Extension
    public static class MansionComputerListener extends ComputerListener {
        @Override
        public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (c instanceof MansionComputer) {
                ((MansionComputer)c).launchedTime = System.currentTimeMillis();
            }
        }

        @Override
        public void onLaunchFailure(Computer c, TaskListener taskListener) throws IOException, InterruptedException {
            if (c instanceof MansionComputer) {
                ((MansionComputer)c).launchedTime = System.currentTimeMillis();
            }
        }
    }
}
