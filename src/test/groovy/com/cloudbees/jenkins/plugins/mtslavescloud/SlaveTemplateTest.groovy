package com.cloudbees.jenkins.plugins.mtslavescloud

import org.junit.Test

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class SlaveTemplateTest {
    @Test
    public void load() {
        def t = SlaveTemplate.load(this.class.getResourceAsStream("machines.json"))
        assert t["lxc-fedora17"].displayName!=null;
        assert t["lxc-fedora17"].spec!=null;
    }
}
