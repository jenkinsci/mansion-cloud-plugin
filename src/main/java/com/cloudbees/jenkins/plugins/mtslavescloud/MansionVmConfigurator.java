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

import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.Label;
import jenkins.model.Jenkins;

import java.io.IOException;

/**
 * Extension point for constructing a request for a virtual machine.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MansionVmConfigurator implements ExtensionPoint {
    // TODO: add template currently being provisioned
    public abstract void configure(MansionCloud mansion, Label label, VirtualMachineSpec spec) throws IOException, InterruptedException;

    public static ExtensionList<MansionVmConfigurator> all() {
        return Jenkins.getInstance().getExtensionList(MansionVmConfigurator.class);
    }
}
