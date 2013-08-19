package com.cloudbees.jenkins.plugins.mtslavescloud;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Keeps track of noteworthy slave allocations.
 *
 * This is the basis for the management UI. This includes all the in-progress allocations that haven't
 * completed, as well as failures that we want to keep around.
 */
public class PlannedMansionSlaveSet implements Iterable<PlannedMansionSlave> {
    /**
     * The actual data store
     */
    private final Set<PlannedMansionSlave> data = new CopyOnWriteArraySet<PlannedMansionSlave>();

    public Iterator<PlannedMansionSlave> iterator() {
        return data.iterator();
    }

    /**
     * Trim the content to remove uninteresting entries.
     *
     * Called by {@link PlannedMansionSlave} when there's a status change.
     */
    /*package*/ void update() {
        for (PlannedMansionSlave s : data) {
            if (!s.isNoteWorthy())
                data.remove(s);
        }
    }

    /**
     * Called when a new {@link PlannedMansionSlave} is created to initiate tracking.
     */
    /*package*/ void onStarted(PlannedMansionSlave p) {
        this.data.add(p);
    }

    public PlannedMansionSlave getDynamic(String id) {
        for (PlannedMansionSlave s : data) {
            if (s.displayName.equals(id))
                return s;
        }
        return null;
    }
}
