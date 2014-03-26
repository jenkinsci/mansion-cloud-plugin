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

package com.cloudbees.jenkins.plugins.mtslavescloud.util;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * A counter which implements exponential backoff
 *
 * @author recampbell
 */
public class BackOffCounter {

    private int numberOfErrors = 0;

    // all units are milliseconds

    private long lastErrorAt = 0;
    private final long maxBackOff;
    private final long firstBackOff;

    /**
     * Type of the mansion this counter is for.
     */
    public final String id;

    /**
     * BackOff with initial and maximum times.
     *
     * @param first The initial backoff period from the first error, in the givenUnit
     * @param max The maximum time to backoff from the previous error
     * @param unit the unit of time of first and max.
     */
    public BackOffCounter(String id, long first, long max, TimeUnit unit) {
        this.id = id;
        maxBackOff = unit.toMillis(max);
        firstBackOff = unit.toMillis(first);
    }

    /**
     * Records an error event.
     */
    public synchronized void recordError() {
        lastErrorAt = System.currentTimeMillis();
        numberOfErrors++;
        LOGGER.log(WARNING,"Will try again in {0} seconds.",
                TimeUnit.MILLISECONDS.toSeconds(getBackOff()));
    }

    /**
     * Determines if we should hold off sending a request now.
     * @return
     */
    public boolean isBackOffInEffect() {
        return System.currentTimeMillis() < getNextAttempt();
    }

    /**
     * When would the back off restriction lift?
     *
     * @return ms since the epoch.
     */
    public long getNextAttempt() {
        return lastErrorAt + getBackOff();
    }

    /**
     * The amount of time to backoff. Mostly for testing.
     * @return
     */
    public long getBackOff() {
        if (numberOfErrors > 0) {
            return Math.min(firstBackOff*(2<<numberOfErrors), maxBackOff);
        } else {
            return 0;
        }
    }

    /**
     * Call this when service responds acceptably.
     */
    public synchronized void clear() {
        numberOfErrors = 0;
        lastErrorAt = 0;
    }

    private static final Logger LOGGER = Logger.getLogger(BackOffCounter.class.getName());
}