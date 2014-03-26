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

package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import hudson.DescriptorExtensionList;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class SlaveTemplateDescriptor extends Descriptor<SlaveTemplate> {

    public static DescriptorExtensionList<SlaveTemplate,SlaveTemplateDescriptor> all() {
        return Jenkins.getInstance().getDescriptorList(SlaveTemplate.class);
    }

    /**
     * Creates a new {@link SlaveTemplate}.
     */
    public abstract SlaveTemplate newInstance(String name);

    /**
     * Do we have multiple clouds configured that requires templates to distinguish them?
     */
    public boolean hasMultipleAccounts() {
        return Jenkins.getInstance().clouds.getAll(MansionCloud.class).size()>1;
    }
}
