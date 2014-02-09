package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import hudson.ExtensionList;
import jenkins.model.Jenkins;
import org.apache.tools.ant.ExtensionPoint;

/**
 * An extension point for controlling the configuration of the mansion-plugin
 * dynamically.
 *
 * @author Ryan Campbell
 */
public abstract class MansionConfiguration extends ExtensionPoint {

    /**
     * Canonical sizes are xlarge, large & small, but
     * marketing has decided that hispeed(xlarge) and standard(large)
     * are easier to communicate externally, so we demonstrate
     * support for those as well.
     */
    public enum Size {
        XLARGE, HISPEED(XLARGE), LARGE, STANDARD(LARGE), SMALL;
        private Size canonical;

        /**
         * Some sizes are synonyms for canonical sizes.
         * @param canonical
         */
        Size(Size canonical) {
            this.canonical = canonical == null ? this : canonical;
        }
        Size() {
            this(null);
        }

        public Size getCanonical() {
            return this.canonical;
        }
    }

    /**
     * Implementations can determine the size to provision
     * when the size is not specified by the user.
     *
     * The value is defined by the first implementation to return
     * a non-null value.
     *
     * @param templateName the name of the template being provisioned.
     * @return
     */
    public Size getDefaultSize(SlaveTemplate templateName) {
        return null;
    }

    public static ExtensionList<MansionConfiguration> all() {
        return Jenkins.getInstance().getExtensionList(MansionConfiguration.class);
    }

}
