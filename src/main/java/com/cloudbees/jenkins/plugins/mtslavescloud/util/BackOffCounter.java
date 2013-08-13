package com.cloudbees.jenkins.plugins.mtslavescloud.util;

import java.util.concurrent.TimeUnit;

/**
 * A counter which implements exponential backoff
 *
 * @author recampbell
 *
 */
public class BackOffCounter {

    private int numberOfErrors = 0;
    private long lastErrorAt = 0;
    private final long maxBackOff;
    private final long firstBackOff;
    private final TimeUnit unit;

    /**
     * BackOff with initial and maximum times.
     *
     * @param first The initial backoff period from the first error, in the givenUnit
     * @param max The maximum time to backoff from the previous error
     * @param givenUnit the unit of time of first and max.
     */
    public BackOffCounter(long first, long max, TimeUnit givenUnit) {
        maxBackOff = max;
        firstBackOff = first;
        unit = givenUnit;
    }

    /**
     * Records an error event.
     */
    public synchronized void recordError() {
        lastErrorAt = System.currentTimeMillis();
        numberOfErrors++;
    }

    /**
     * Determines if we should try another request now.
     * @return
     */
    public boolean shouldBackOff() {
        return System.currentTimeMillis() <
                lastErrorAt + unit.toMillis(getBackOff());
    }

    /**
     * The amount of time to backoff in the givenUnit. Mostly for testing.
     * @return
     */
    public long getBackOff() {
        if (numberOfErrors > 0) {
            return (long)Math.min(Math.pow(firstBackOff, numberOfErrors) - 1, maxBackOff);
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

}