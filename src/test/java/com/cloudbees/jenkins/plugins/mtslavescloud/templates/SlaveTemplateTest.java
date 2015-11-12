package com.cloudbees.jenkins.plugins.mtslavescloud.templates;

import hudson.model.Label;
import org.junit.ClassRule;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Theories.class)
public class SlaveTemplateTest {

    @ClassRule
    public static JenkinsRule j = new JenkinsRule();

    @Theory
    public void matches(Mapping mapping) throws Exception {
        final SlaveTemplateList list = SlaveTemplateList.get();
        boolean found = false;
        for (SlaveTemplate t : list.getItems()) {
            if (mapping.name.equals(t.getName())) {
                found = true;
                assertThat(t.matches(Label.parseExpression(mapping.label)), is(mapping.matches));
            }
        }
        assertThat("we have a template with the name: " + mapping.name, found, is(true));
    }

    @DataPoints
    public static Mapping[] data() {
        return new Mapping[]{
                matches("lxc-fedora17", "standard"),
                matches("lxc-fedora17", "lxc-fedora17 && standard"),
                matches("lxc-fedora17", "lxc-fedora17 || standard"),
                matches("lxc-fedora17", "lxc-fedora17 || hi-speed"),
                matches("lxc-fedora17", "hi-speed"),
                matches("android", "android && standard"),
                noMatch("android", "standard"),
                matches("osx", "osx && standard"),
                noMatch("osx", "standard"),
                };
    }

    public static Mapping matches(String name, String expression) {
        return new Mapping(name, expression, true);
    }

    public static Mapping noMatch(String name, String expression) {
        return new Mapping(name, expression, false);
    }

    public static class Mapping {
        private final String name;
        private final String label;
        private final boolean matches;

        public Mapping(String name, String label, boolean matches) {
            this.name = name;
            this.label = label;
            this.matches = matches;
        }
    }
}
