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
import hudson.model.Label;
import hudson.util.VariableResolver;
import net.sf.json.JSONObject;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.annotate.JsonProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * {@link SlaveTemplate} for those machine types that are pre-bundled in the plugin.
 *
 * It offers very limited amount of configuration.
 *
 * @author Kohsuke Kawaguchi
 */
public class BuiltinSlaveTemplate extends SlaveTemplate {
    private transient String mansionType;

    private transient List<String> persistentFileSystems;

    private transient String spec;

    private transient boolean nameMatchRequired;

    public BuiltinSlaveTemplate(String name) {
        super(name);
    }

    @JsonProperty
    private void setMansionType(String t) {
        this.mansionType = t;
    }

    @JsonProperty("id")
    protected void _(String s) {
        // ignoring the id attribute in machines.json
    }

    @JsonProperty
    protected void setSpec(JsonNode spec) {
        this.spec = spec.toString();
    }

    @JsonProperty
    public void setDisplayName(String displayName) throws IOException {
        super.setDisplayName(displayName);
    }

    @Override
    public List<String> getPersistentFileSystems() {
        return Collections.unmodifiableList(persistentFileSystems);
    }

    @JsonProperty("persistentFileSystems")
    private void setPersistentFileSystems(List<String> persistentFileSystems) {
        this.persistentFileSystems = persistentFileSystems;
    }

    public boolean isNameMatchRequired() {
        return nameMatchRequired;
    }

    @JsonProperty("nameMatchRequired")
    public void setNameMatchRequired(boolean nameMatchRequired) {
        this.nameMatchRequired = nameMatchRequired;
    }

    @Override
    public JSONObject createSpec() {
        return JSONObject.fromObject(spec);
    }

    @Override
    public String getMansionType() {
        return mansionType;
    }

    /**
     * Used to test if this built-in slave template is still valid.
     */
    public boolean hasSpec() {
        return spec!=null;
    }

    /**
     * Checks if this slave template matches the given label.
     *
     * This recognizes the size specifier "small" and "large" aside from the main label
     */
    @Override
    public boolean matches(Label label) {
        if (nameMatchRequired) {
            final boolean[] haveName = new boolean[1];
            return label.matches(new VariableResolver<Boolean>() {
                public Boolean resolve(String name) {
                    if (name.equals(getLabel())) {
                        haveName[0] = true;
                    }
                    return name.equals(getLabel()) || name.equals("small") || name.equals("large") || name
                            .equals("xlarge")
                            || name.equals("standard") || name.equals("hi-speed");
                }
            }) && haveName[0];
        }
        return super.matches(label);
    }

    /**
     * Checks if the given label matches this slave template with the specific hardware size.
     */
    @Override
    public boolean matches(Label label, final String size) {
        if (nameMatchRequired) {
            final boolean[] haveName = new boolean[1];
            return label.matches(new VariableResolver<Boolean>() {
                public Boolean resolve(String name) {
                    if (name.equals(getLabel())) {
                        haveName[0] = true;
                    }
                    return name.equals(getLabel()) || name.equals(size);
                }
            }) && haveName[0];
        }
        return super.matches(label, size);
    }

    @Extension
    public static class DescriptorImpl extends SlaveTemplateDescriptor {
        @Override
        public BuiltinSlaveTemplate newInstance(String name) {
            // can't manually create this guy
            throw new UnsupportedOperationException();
        }

        @Override
        public String getDisplayName() {
            return "Built-in Slave Template";
        }
    }
}
