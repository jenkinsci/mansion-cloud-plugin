package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.jenkins.plugins.mtslavescloud.*;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.Util;
import net.sf.json.JSONObject;

import java.net.URL;
import java.util.List;

/**
 * The most up-to-date snapshot that we carry over for one file system described in {@link SlaveTemplate}.
 *
 * <p>
 * Some of the file systems {@link SlaveTemplate}s describe aren't used like an ephemeral file system.
 * Instead, their snapshots are taken at the end and when we create a new slave from the same template later,
 * we'll start from those snapshots. If a snapshot is no longer available, we fall back to the starting
 * point snapshot described in the template. In effect, we treat such file systems as warm caches.
 * A good example of this is a workspace.
 *
 * <p>
 * Since this activity generally produces a sequence of snapshots that form a single lineage, this class
 * is named accordingly.
 *
 * TODO: remember last N snapshots, not just one, and tell the server that we are OK with any of them.
 *
 * @author Kohsuke Kawaguchi
 */
class FileSystemLineage {
    /**
     * Where is this file system mounted in the slave?
     */
    private final String path;

    /**
     * Last snapshot of this lineage.
     */
    private URL snapshot;

    FileSystemLineage(String path, URL snapshot) {
        this.path = path;
        this.snapshot = snapshot;
    }

    String getPath() {
        return path;
    }

    public void applyTo(VirtualMachineSpec spec) {
        spec.fs(snapshot,path);
    }
}
