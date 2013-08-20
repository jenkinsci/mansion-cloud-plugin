package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.util.IOUtils;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.io.IOUtils.*;

/**
 * Configuration of multi-tenant slave, described in a format that {@link VirtualMachineSpec} understands.
 *
 * <p>
 * This is how people can craft, share, and reuse the slave definition, and therefore there is no
 * details specific to each Jenkins installations (such as which label this slave is available as.)
 *
 * @author Kohsuke Kawaguchi
 */
class SlaveTemplate___ {
    /**
     * Unique ID that globally distinguishes this template.
     */
    public String id;
    /**
     * Human readable name of this template.
     */
    public String displayName;
    /**
     * Configuration JSON object to be passed to {@link VirtualMachineRef#setup(VirtualMachineSpec)}
     */
    public JSONObject spec;
    /**
     * Type of the mansion that this template lives in.
     */
    public String mansion;

    /**
     * Of the file systems that are specified in {@link #spec}, designate ones that
     * should be carried over to the next session.
     */
    public List<String> persistentFileSystems = new ArrayList<String>();

    /**
     * Cloud that this template belongs to.
     */
    private transient MansionCloud owner;

    /*package*/ void postInit(MansionCloud cloud) {
        this.owner = cloud;
    }

    public MansionCloud getOwner() {
        return owner;
    }

    public void populate(VirtualMachineSpec spec) {
        JSONArray configs = this.spec.optJSONArray("configs");
        if (configs!=null) {
            spec.configs.addAll(configs);
        }
    }


    public static Map<String,SlaveTemplate> load(InputStream in) throws IOException {
        try {
            JsonConfig jsc = new JsonConfig();
            jsc.setIgnorePublicFields(false);
            JSONObject js = JSONObject.fromObject(IOUtils.toString(in));
            TemplateList list = (TemplateList) JSONObject.toBean(js, TemplateList.class);
            Map<String,SlaveTemplate> r = new LinkedHashMap<String, SlaveTemplate>();
            for (int i = 0; i < list.templates.length; i++) {
                SlaveTemplate t = list.templates[i];
                t.spec = js.getJSONArray("templates").getJSONObject(i).getJSONObject("spec");
                r.put(t.id,t);
                // json-lib fails to bind a property whose type is JSONObject, so fix that up
                t.spec = findSpec(js,t.id);
            }
            return r;
        } finally {
            closeQuietly(in);
        }
    }

    private static JSONObject findSpec(JSONObject js, String id) {
        for (JSONObject o : (List< JSONObject>)(List)js.getJSONArray("templates")) {
            if (o.getString("id").equals(id))
                return o.getJSONObject("spec");
        }
        return null;
    }

    public static final class TemplateList {
        public SlaveTemplate[] templates;
    }
}
