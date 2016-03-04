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
     * @return the number of mansion slaves that are currently provisioned
     */
    public int getInProvisioningCount() {
        int result = 0;
        for (PlannedMansionSlave s : data) {
            if (s.getProblem() == null) {
                result++;
            }
        }
        return result;
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
            if (s.getDisplayName().equals(id))
                return s;
        }
        return null;
    }

    /**
     * Only keep up to N failures.
     */
    public static int FAILURE_CAP = Integer.getInteger(PlannedMansionSlave.class.getName()+".failureCap",8);
}
