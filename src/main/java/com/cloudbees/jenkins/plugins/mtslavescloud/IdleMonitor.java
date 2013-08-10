package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.concurrent.Callable;

import com.cloudbees.hudson.plugins.Config;
import jenkins.model.Jenkins;

/**
 * Whenever a job completes, wait a bit and check all computers.
 *
 * @author rcampbell
 *
 */
@Extension
public class IdleMonitor extends RunListener<Run> {

    public IdleMonitor() {
        super(Run.class);
    }

    @Override
    public void onCompleted(Run r, TaskListener listener) {
        Computer.threadPoolForRemoting.submit(new Callable<Void>() {
            public Void call() throws Exception {
                // sleep long enough for a computer to become idle, if possible
                Thread.sleep(MansionRetentionStrategy.TIMEOUT + 2000);
                for (Computer c : Jenkins.getInstance().getComputers()) {
                    if (c instanceof MansionComputer) {
                        c.getRetentionStrategy().check(c);
                    }
                }
                return null;
            }
        });
    }

}
