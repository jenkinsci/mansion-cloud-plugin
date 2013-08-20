package com.cloudbees.jenkins.plugins.mtslavescloud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
    /*package*/
    @SuppressWarnings("ThrowableResultOfMethodCallIgnored")
    void update() {
        List<PlannedMansionSlave> failures = new ArrayList<PlannedMansionSlave>();
        for (PlannedMansionSlave s : data) {
            if (!s.isNoteWorthy())
                data.remove(s);
            if (s.getProblem()!=null)
                failures.add(s);
        }

        // only keep up to N failures to avoid cluttering
        // delete from front to prefer newer failures
        if (failures.size()>FAILURE_CAP) {
            data.removeAll(failures.subList(0,failures.size()-FAILURE_CAP));
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

    /**
     * Only keep up to N failures.
     */
    public static int FAILURE_CAP = Integer.getInteger(PlannedMansionSlave.class.getName()+".failureCap",8);
}
