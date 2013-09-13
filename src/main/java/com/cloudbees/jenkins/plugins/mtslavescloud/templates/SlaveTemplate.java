package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import hudson.model.AbstractItem;
import hudson.model.Describable;
import hudson.model.Descriptor;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class SlaveTemplate extends AbstractItem implements Describable<SlaveTemplate> {

    private boolean disabled;
    private String account;

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
                return name.equals(getLabel()) || name.equals("small") || name.equals("large");
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
