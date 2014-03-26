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
