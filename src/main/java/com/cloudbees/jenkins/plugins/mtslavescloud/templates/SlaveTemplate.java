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
import com.cloudbees.jenkins.plugins.mtslavescloud.MansionConfiguration;
import com.cloudbees.mtslaves.client.HardwareSpec;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.model.AbstractItem;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Label;
import hudson.security.Permission;
import hudson.util.FormApply;
import hudson.util.VariableResolver;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.cloudbees.jenkins.plugins.mtslavescloud.MansionConfiguration.Size.*;
import static java.util.Arrays.asList;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class SlaveTemplate extends AbstractItem implements Describable<SlaveTemplate> {

    private boolean disabled;
    private String account;

    /**
     * Allow the definition of a default size for each template so that users don't have
     * to edit each and every job.
     */
    private MansionConfiguration.Size defaultSize;

    protected SlaveTemplate(String name) {
        super(SlaveTemplateList.get(), name);
    }

    /**
     * Nodes provisioned from this template will have this label.
     */
    public final String getLabel() {
        return getName();
    }

    /***
     * Is this template instantiable?
     */
    public boolean isEnabled() {
        return !disabled;
    }

    /**
     * Gets the account used to provision this slave template
     */
    public String getAccount() {
        return account;
    }

    /**
     * Populate the default size based on the #{@link MansionConfiguration#getDefaultSize(SlaveTemplate)}
     * for the first time, then use whatever the user has saved.
     */
    public MansionConfiguration.Size getDefaultSize() {
        if (defaultSize == null) {
            return getConfiguredDefaultSize();
        } else {
            return defaultSize;
        }
    }

    public List<MansionConfiguration.Size> getAvailableSizes() {
        List<MansionConfiguration.Size> available = new ArrayList<MansionConfiguration.Size>();
        available.add(HISPEED);
        available.add(STANDARD);
        if (getConfiguredDefaultSize() == SMALL) {
            available.add(SMALL);
        }
        return available;
    }

    private MansionConfiguration.Size getConfiguredDefaultSize() {
        for (MansionConfiguration config : Jenkins.getInstance().getExtensionList(MansionConfiguration.class)) {
            MansionConfiguration.Size configSize = config.getDefaultSize(this);
            if (configSize != null) {
                return configSize;
            }
        }
        return MansionConfiguration.Size.HISPEED;
    }

    /**
     * Configuration JSON object to be passed to {@link VirtualMachineRef#setup(VirtualMachineSpec)}
     */
    public abstract JSONObject createSpec();

    /**
     * Type of the mansion that this template lives in.
     */
    public abstract String getMansionType();

    /**
     * Of the file systems that are specified in {@link #createSpec()}, designate ones that
     * should be carried over to the next session. Read-only.
     */
    public List<String> getPersistentFileSystems() {
        return Collections.emptyList();
    }

    /**
     * Gets the cloud to provision this template from.
     *
     * We anticipate that some of the templates might require access to file systems or other data
     * that's only visible to specific account.
     */
    public MansionCloud getMansion() {
        List<MansionCloud> clouds = Jenkins.getInstance().clouds.getAll(MansionCloud.class);
        for (MansionCloud c : clouds) {
            String a = c.getAccount();
            if (a==null || a.equals(account))   // a==null means there's only one account so there should be no restriction
                return c;
        }

        // fall back to the first one in the hope that it'll work
        return clouds.isEmpty() ? null : clouds.get(0);
    }

    /**
     * Checks if this slave template matches the given label.
     *
     * This recognizes the size specifier "small" and "large" aside from the main label
     */
    public boolean matches(Label label) {
        return label.matches(new VariableResolver<Boolean>() {
            public Boolean resolve(String name) {
                return name.equals(getLabel()) || name.equals("small") || name.equals("large") || name.equals("xlarge")
                || name.equals("standard") || name.equals("hi-speed");
            }
        });
    }

    /**
     * Checks if the given label matches this slave template with the specific hardware size.
     */
    public boolean matches(Label label, final String size) {
        return label.matches(new VariableResolver<Boolean>() {
            public Boolean resolve(String name) {
                return name.equals(getLabel()) || name.equals(size);
            }
        });
    }


    /**
     * Loads the current clan of {@link FileSystemLineage}s this master has for this slave template.
     */
    public FileSystemClan getClan() throws IOException {
        FileSystemClan clan = new FileSystemClan(getMansion(),this);
        clan.load();
        return clan;
    }

    public void populate(VirtualMachineSpec spec) {
        JSONArray configs = createSpec().optJSONArray("configs");
        if (configs!=null) {
            spec.configs.addAll(configs);
        }
    }



    public SlaveTemplateDescriptor getDescriptor() {
        return (SlaveTemplateDescriptor) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    public HttpResponse doConfigSubmit(StaplerRequest req) throws ServletException, IOException, Descriptor.FormException {
        checkPermission(CONFIGURE);
        JSONObject form = req.getSubmittedForm();
        submit(form);
        save();
        return FormApply.success(".");
    }

    protected void submit(JSONObject json) throws ServletException, Descriptor.FormException {
        this.disabled = !json.has("enabled");
        this.account = json.optString("account");
        this.displayName = json.optString("displayName");
        this.defaultSize = MansionConfiguration.Size.valueOf(json.optString("defaultSize"));
    }



    /**
     * @deprecated
     *      Why are you calling a method that always return an empty list?
     */
    @Override
    public Collection<? extends Job> getAllJobs() {
        return Collections.emptyList();
    }

    // for now, I'm not defining a new set of permissions
    public static Permission CONFIGURE = Jenkins.ADMINISTER;
}
