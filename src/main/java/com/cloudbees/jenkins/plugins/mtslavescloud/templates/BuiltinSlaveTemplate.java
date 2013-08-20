package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;
import net.sf.json.JSONObject;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.annotate.JsonProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
public class BuiltinSlaveTemplate extends SlaveTemplate {
    @JsonProperty
    private transient String mansionType;

    @JsonProperty
    private transient List<String> persistentFileSystems;

    private transient String spec;

    public BuiltinSlaveTemplate(String name) {
        super(name);
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
