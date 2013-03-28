package com.cloudbees.jenkins.plugins.mtslavescloud;

import hudson.slaves.SlaveComputer;
import org.kohsuke.stapler.HttpResponse;

import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class MansionComputer extends SlaveComputer {
    MansionComputer(MansionSlave slave) {
        super(slave);
    }

    @Override
    public MansionSlave getNode() {
        return (MansionSlave)super.getNode();
    }

    @Override
    public HttpResponse doDoDelete() throws IOException {
        HttpResponse r = super.doDoDelete();
        getNode().terminate();
        return r;
    }
}
