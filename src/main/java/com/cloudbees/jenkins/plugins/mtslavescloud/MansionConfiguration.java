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

package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import hudson.ExtensionList;
import jenkins.model.Jenkins;
import org.apache.tools.ant.ExtensionPoint;

import java.util.Locale;

/**
 * An extension point for controlling the configuration of the mansion-plugin
 * dynamically.
 *
 * @author Ryan Campbell
 */
public abstract class MansionConfiguration extends ExtensionPoint {

    /**
     * Canonical sizes are xlarge, large & small, but
     * marketing has decided that hi-speed(xlarge) and standard(large)
     * are easier to communicate externally, so we demonstrate
     * support for those as well.
     */
    public enum Size {
        XLARGE("hi-speed"), HISPEED(XLARGE), LARGE("standard"), STANDARD(LARGE), SMALL("small");
        private String label;
        private Size canonical;

        /**
         * Some sizes are synonyms for canonical sizes.
         * @param canonical
         */
        Size(Size canonical) {
            this.canonical = canonical;
        }

        /**
         * Only canonical sizes have labels.
         * @param label
         */
        Size(String label) {
            this.canonical = this;
            this.label = label;
        }

        /**
         * The marketing/user-friendly label for this hardware size.
         *
         * @return
         */
        public String getLabel() {
            return this.canonical.label;
        }

        /**
         * The {@link com.cloudbees.mtslaves.client.HardwareSpec#size}
         * @return
         */
        public String getHardwareSize() {
            return this.canonical.name().toLowerCase(Locale.ENGLISH);
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
