package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.model.Computer;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.SlaveComputer;
import org.acegisecurity.Authentication;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class MansionComputer extends SlaveComputer {
    // MansionSlave, once gets created, is never reconfigured, so we can keep a reference like this.
    private final MansionSlave slave;

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

    @Override
    public MansionSlave getNode() {
        return (MansionSlave)super.getNode();
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
}
