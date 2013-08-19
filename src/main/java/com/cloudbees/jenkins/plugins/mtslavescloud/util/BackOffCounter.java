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