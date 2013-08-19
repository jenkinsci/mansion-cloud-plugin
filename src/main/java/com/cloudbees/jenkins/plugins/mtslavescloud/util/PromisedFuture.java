package com.cloudbees.jenkins.plugins.mtslavescloud.util;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Future} that was handed out before the actual future is provided.
 *
 * A proxy of {@link Future}.
 *
 * @author Kohsuke Kawaguchi
 */
public class PromisedFuture<V> implements Future<V> {
    private volatile Future<? extends V> base;

    private Boolean cancel;

    public synchronized void setBase(Future<? extends V> base) {
        this.base = base;

        if (cancel!=null)   // differed cancellation
            base.cancel(cancel);

        notifyAll();    // wake up everyone that we have the data
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (base==null) {
            cancel = mayInterruptIfRunning;
            return false;
        } else {
            return base.cancel(mayInterruptIfRunning);
        }
    }

    public boolean isCancelled() {
        if (base==null)
            return cancel!=null;
        else
            return base.isCancelled();
    }

    public boolean isDone() {
        if (base==null)
            return cancel!=null;
        else
            return base.isDone();
    }

    public V get() throws InterruptedException, ExecutionException {
        synchronized (this) {
            while (base==null)
                wait();
        }
        return base.get();
    }

    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        synchronized (this) {
            if (base==null) {
                wait(unit.toMillis(timeout));
                if (base==null)
                    throw new TimeoutException();
            }
        }
        return base.get(timeout, unit);
    }
}
