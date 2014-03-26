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

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor.FormException;
import net.sf.json.JSONObject;

import javax.servlet.ServletException;
import java.util.Arrays;
import java.util.List;

/**
 * {@link SlaveTemplate} that lets the user defines the spec JSON. Experimental.
 *
 * @author Kohsuke Kawaguchi
 */
public class LocalSlaveTemplate extends SlaveTemplate {
    private String mansionType;
    private String definition;
    /**
     * List of persistent file systems separated by NL.
     */
    private String persistentFileSystems;

    public LocalSlaveTemplate(String displayName) {
        super(displayName);
    }

    public String getDefinition() {
        return definition;
    }

    @Override
    public JSONObject createSpec() {
        return JSONObject.fromObject(getDefinition());
    }

    @Override
    public String getMansionType() {
        return mansionType;
    }

    public String getPersistentFileSystemsStr() {
        return persistentFileSystems;
    }

    @Override
    public List<String> getPersistentFileSystems() {
        return Arrays.asList(persistentFileSystems.split("[\r\n]+"));
    }

    @Override
    protected void submit(JSONObject json) throws ServletException, FormException {
        super.submit(json);
        definition = json.getString("definition");
        mansionType = json.getString("mansionType");
        persistentFileSystems = Util.fixEmptyAndTrim(json.getString("persistentFileSystemsStr"));
    }

    @Extension
    public static class DescriptorImpl extends SlaveTemplateDescriptor {
        @Override
        public LocalSlaveTemplate newInstance(String name) {
            return new LocalSlaveTemplate(name);
        }

        @Override
        public String getDisplayName() {
            return "Local Slave Template";
        }
    }
}
