package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.model.AbstractItem;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.security.Permission;
import hudson.util.FormApply;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class SlaveTemplate extends AbstractItem implements Describable<SlaveTemplate> {

    protected SlaveTemplate(String name) {
        super(SlaveTemplateList.get(), name);
    }

    public SlaveTemplateDescriptor getDescriptor() {
        return (SlaveTemplateDescriptor) Jenkins.getInstance().getDescriptorOrDie(getClass());
    }

    public HttpResponse doConfigSubmit(StaplerRequest req) throws ServletException, IOException, Descriptor.FormException {
        checkPermission(CONFIGURE);
        JSONObject form = req.getSubmittedForm();
        submit(req, form);
        save();
        return FormApply.success(".");
    }

    protected abstract void submit(StaplerRequest request, JSONObject json) throws ServletException, Descriptor.FormException;



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
