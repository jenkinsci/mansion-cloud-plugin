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

package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import com.cloudbees.mtslaves.client.FileSystemRef;
import com.cloudbees.mtslaves.client.SnapshotRef;
import com.cloudbees.mtslaves.client.VirtualMachine;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import com.cloudbees.mtslaves.client.properties.FileSystemsProperty;
import hudson.XmlFile;
import hudson.util.HttpResponses;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * A set of {@link FileSystemLineage}s that are used together for a single {@link SlaveTemplate}.
 *
 * @author Kohsuke Kawaguchi
 * @author Ryan Campbell
 */
public class FileSystemClan implements Iterable<FileSystemLineage> {
    private final MansionCloud cloud;
    /**
     * A clan is a record of how this master have used a template, so it's tied to one.
     */
    private final transient SlaveTemplate template;

    private Long lastDestroyDate = null;

    private final List<FileSystemLineage> lineages = new ArrayList<FileSystemLineage>();

    FileSystemClan(MansionCloud cloud, SlaveTemplate template) {
        if (cloud==null)    throw new IllegalArgumentException();
        this.cloud = cloud;
        this.template = template;
    }

    public boolean isEmpty() {
        return lineages.isEmpty();
    }

    public Iterator<FileSystemLineage> iterator() {
        return lineages.iterator();
    }

    /**
     * The file in which we store the latest snapshots of workspace file systems.
     */
    private XmlFile getPersistentFileSystemRecordFile() {
        return new XmlFile(new File(template.getRootDir(),"clan.xml"));
    }

    public void add(FileSystemLineage newFsl) {
        for (FileSystemLineage existingFsl : lineages) {
            if (newFsl.osbsoletes(existingFsl)) {
                lineages.remove(existingFsl);

                // since we will be forgetting about this snapshot, tell mansion that it can be gone now
                try {
                    SnapshotRef ref = existingFsl.getRef(cloud);
                    ref.dispose();
                    LOGGER.info("Disposed snapshot "+ref.url);
                } catch (IOException e) {
                    LOGGER.log(WARNING, "Failed to dispose "+existingFsl.getSnapshot(),e);
                } catch (OauthClientException e) {
                    LOGGER.log(WARNING, "Failed to dispose "+existingFsl.getSnapshot(),e);
                }
                break;
            }
        }
        lineages.add(newFsl);
    }

    public void load() throws IOException {
        XmlFile props = getPersistentFileSystemRecordFile();
        if (props.exists()) {
            props.unmarshal(this);
        }
    }

    public void save() throws IOException {
        getPersistentFileSystemRecordFile().write(this);
    }

    public void applyTo(VirtualMachineSpec spec, VirtualMachineRef vm) {
        for (FileSystemLineage e : this) {
            e.applyTo(spec,vm);
        }
    }

    /**
     * Takes snapshots of the current file systems attached to the given VM and
     * use that as the latest generation of this clan.
     *
     * If the clan has been destroyed since the vmCreatedDate, no snapshot will be
     * taken. This can happen when snapshots are 'cleared' while a VM is running.
     */
    public void update(VirtualMachine vm, Long vmCreatedDate) {
        FileSystemsProperty fsp = vm.getProperty(FileSystemsProperty.class);
        if (fsp==null)  return;
        if (lastDestroyDate != null && lastDestroyDate > vmCreatedDate) return;

        for (String persistedPath : template.getPersistentFileSystems()) {
            URL fs = fsp.getFileSystemUrlFor(persistedPath);
            if (fs==null)   continue;   // shouldn't happen, but let's be defensive

            try {
                FileSystemRef fsr = new FileSystemRef(fs,cloud.createAccessToken(fs));
                SnapshotRef snapshot = fsr.snapshot();
                add(new FileSystemLineage(persistedPath,snapshot.url));
            } catch (IOException e) {
                LOGGER.log(WARNING, "Failed to take snapshot of "+fs,e);
            } catch (OauthClientException e) {
                LOGGER.log(WARNING, "Failed to take snapshot of "+fs,e);
            }
        }
        try {
            save();
        } catch (IOException e) {
            LOGGER.log(WARNING, "Failed to persist the clan",e);
        }
    }

    /**
     * Deletes all the current snapshots and reverts to the clean image.
     */
    @RequirePOST
    public HttpResponse doDispose() throws IOException, OauthClientException {
        template.checkPermission(SlaveTemplate.CONFIGURE);
        for (FileSystemLineage l: this) {
            try {
                l.getRef(cloud).dispose();
            } catch (IOException ioe) {
                LOGGER.log(Level.WARNING, "Failed to delete snapshot " +l.getSnapshot(),ioe);
            } catch (OauthClientException oce) {
                LOGGER.log(Level.WARNING, "Failed to delete snapshot " +l.getSnapshot(), oce);
            }
        }
        try {
            lineages.clear();
            lastDestroyDate = System.currentTimeMillis();
            save();
        } catch (IOException e) {
            LOGGER.log(WARNING, "Failed to persist the clan",e);
        }

        return HttpResponses.forwardToPreviousPage();
    }

    private static final Logger LOGGER = Logger.getLogger(FileSystemClan.class.getName());
}
