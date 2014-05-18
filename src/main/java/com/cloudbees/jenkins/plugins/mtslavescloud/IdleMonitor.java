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

import hudson.Extension;
import hudson.model.Run;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

import java.util.concurrent.Callable;

import jenkins.model.Jenkins;

/**
 * Whenever a job completes, wait a bit and check all computers.
 *
 * @author rcampbell
 *
 */
@Extension
public class IdleMonitor extends RunListener<Run> {

    public IdleMonitor() {
        super(Run.class);
    }

    @Override
    public void onFinalized(Run run) {
        Computer.threadPoolForRemoting.submit(new Callable<Void>() {
            public Void call() throws Exception {
                // sleep long enough for a computer to become idle, if possible
                Thread.sleep(MansionRetentionStrategy.TIMEOUT + 2000);
                for (Computer c : Jenkins.getInstance().getComputers()) {
                    if (c instanceof MansionComputer) {
                        c.getRetentionStrategy().check(c);
                    }
                }
                return null;
            }
        });
    }

}
