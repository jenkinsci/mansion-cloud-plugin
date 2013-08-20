package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.Extension;
import hudson.model.AbstractModelObject;
import hudson.model.ItemGroup;
import hudson.model.ItemGroupMixIn;
import hudson.model.RootAction;
import hudson.model.listeners.ItemListener;
import hudson.security.Permission;
import hudson.util.FormValidation;
import hudson.util.Function1;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import jenkins.model.ModelObjectWithContextMenu;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static javax.servlet.http.HttpServletResponse.*;

/**
 * Adds a list of slave templates to the UI.
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class SlaveTemplateList extends AbstractModelObject implements ItemGroup<SlaveTemplate>, RootAction, ModelObjectWithContextMenu {
    private final ConcurrentMap<String,SlaveTemplate> templates = new ConcurrentHashMap<String, SlaveTemplate>();

    public SlaveTemplateList() {
        Jenkins.getInstance().lookup.set(SlaveTemplateList.class,this);
        load();
    }

    public static SlaveTemplateList get() {
        return Jenkins.lookup(SlaveTemplateList.class);
    }
    /**
     * Loads all the templates
     */
    public void load() {
        Map<String,SlaveTemplate> r = new HashMap<String, SlaveTemplate>();

        // user-defined models
        r.putAll(ItemGroupMixIn.<String,SlaveTemplate>loadChildren(this, getRootDir(), new Function1<String, SlaveTemplate>() {
            public String call(SlaveTemplate m) {
                return m.getName();
            }
        }));
        if (!r.isEmpty()) {
            LOGGER.log(Level.INFO, "{0} template(s) loaded", r.size());
        }

        templates.putAll(r);
    }

    public String getFullName() {
        return "Cloud Slave Templates";
    }

    public String getFullDisplayName() {
        return getFullName();
    }

    /**
     * Hide from the menu if the user cannot access models.
     */
    public String getIconFileName() {
        // TODO: think about a better icon
        return Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER) ? "gear.png" : null;
    }

    public String getUrlName() {
        return "cloudSlaveTemplates";
    }

    public String getSearchUrl() {
        return getUrl();
    }

    public Collection<SlaveTemplate> getItems() {
        return templates.values();
    }

    public String getUrl() {
        return getUrlName()+"/";
    }

    public String getUrlChildPrefix() {
        return ".";
    }

    /**
     * Alias for {@link #getItem(String)}
     */
    public SlaveTemplate get(String name) {
        return getItem(name);
    }

    public final SlaveTemplate getItem(String name) {
        return templates.get(name);
    }

    public SlaveTemplate getDynamic(String token) {
        return getItem(token);
    }

    public File getRootDirFor(SlaveTemplate child) {
        return new File(getRootDir(),child.getName());
    }

    public String getDisplayName() {
        return getFullDisplayName();
    }

    public ContextMenu doContextMenu(StaplerRequest request, StaplerResponse response) throws Exception {
        return new ContextMenu().from(this, request, response);
    }

    public File getRootDir() {
        return new File(Jenkins.getInstance().getRootDir(),getUrlName());
    }

    public void save() throws IOException {
        // nothing to save, so no-op.
    }



    public FormValidation doCheckName(@QueryParameter String name) {
        if (name!=null && templates.containsKey(name))
            return FormValidation.error("Slave template '%s' already exists", name);
        return FormValidation.ok();
    }

    public synchronized HttpResponse doCreateItem( @QueryParameter String name, @QueryParameter String mode, @QueryParameter String from ) throws IOException, ServletException {
        final Jenkins app = Jenkins.getInstance();
        app.checkPermission(CREATE);

        SlaveTemplate result;
        if (mode==null)
            return HttpResponses.error(SC_BAD_REQUEST,"No mode specified");
        if(mode.equals("copy")) {
            SlaveTemplate src = get(from);
            if(src==null)
                return userError("No such template exists: "+from);

            // copy through XStream
            String xml = Jenkins.XSTREAM.toXML(src);
            result = (SlaveTemplate) Jenkins.XSTREAM.fromXML(xml);
            result.onLoad(this, name);
            result.save();
            templates.put(result.getName(),result);
        } else {
            result = createTemplate(SlaveTemplateDescriptor.all().find(mode), name);
        }

        // send the browser to the config page
        return HttpResponses.redirectTo(result.getName()+"/configure");
    }

    // TODO: push to core
    private HttpResponse userError(final String message) {
        return new HttpResponse() {
            public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
                sendError(message,req,rsp);
            }
        };
    }

    /**
     * Convenience method to create a new empty model.
     */
    public synchronized <T extends SlaveTemplate> T createTemplate(Class<T> templateType, String name)
            throws IOException {
        return templateType.cast(createTemplate((SlaveTemplateDescriptor) Jenkins.getInstance().getDescriptorOrDie(templateType), name));
    }

    public synchronized SlaveTemplate createTemplate( SlaveTemplateDescriptor descriptor, String name )
            throws IOException {
        SlaveTemplate t;
        try {
            t = descriptor.newInstance(name);
            t.setDisplayName(name);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }

        t.save();
        templates.put(t.getName(), t);
        t.onCreatedFromScratch();

        ItemListener.fireOnCreated(t);

        return t;
    }

    public Set<String> getAllNames() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * Returns {@link SlaveTemplateDescriptor}s that the user can instantiate.
     */
    public List<SlaveTemplateDescriptor> getInstantiableTemplateDescriptors() {
        List<SlaveTemplateDescriptor> r = new ArrayList<SlaveTemplateDescriptor>(SlaveTemplateDescriptor.all());
        r.remove(Jenkins.getInstance().getDescriptor(BuiltinSlaveTemplate.class));  // can't instantiate this
        return r;
    }

    public void onRenamed(SlaveTemplate item, String oldName, String newName) throws IOException {
        templates.remove(oldName);
        templates.put(newName,item);
    }

    public void onDeleted(SlaveTemplate item) throws IOException {
        templates.remove(item.getName());
    }


    private static final Logger LOGGER = Logger.getLogger(SlaveTemplateList.class.getName());

    // for now, I'm not defining a new set of permissions
    public static final Permission CREATE = Jenkins.ADMINISTER;
}
