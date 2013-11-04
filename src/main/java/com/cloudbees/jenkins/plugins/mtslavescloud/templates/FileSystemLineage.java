package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import com.cloudbees.mtslaves.client.SnapshotRef;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.AbortException;

import java.net.URL;

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
public class FileSystemLineage {
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

    public String getPath() {
        return path;
    }

    public URL getSnapshot() {
        return snapshot;
    }

    void applyTo(VirtualMachineSpec spec, VirtualMachineRef vm) {
        if (snapshot.getHost().equals(vm.url.getHost()))
            spec.fs(snapshot,path);
    }

    SnapshotRef getRef(MansionCloud mansion) throws AbortException, OauthClientException {
        return new SnapshotRef(snapshot,mansion.createAccessToken(snapshot));
    }

    /**
     * Tests whether an existing snapshot can be logically destroyed.
     *
     * @param other
     * @return true if the other can be safely destroyed.
     */
    public boolean osbsoletes(FileSystemLineage other) {
        return other.path.equals(this.path) && other.snapshot.getHost().equals(this.snapshot.getHost());
    }
}
