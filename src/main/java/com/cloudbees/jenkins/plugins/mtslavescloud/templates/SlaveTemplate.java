package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.model.AbstractItem;
import hudson.model.Describable;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.security.Permission;
import jenkins.model.Jenkins;

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
