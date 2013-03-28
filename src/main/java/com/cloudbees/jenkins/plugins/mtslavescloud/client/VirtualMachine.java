package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import net.sf.json.JSONObject;

/**
 * @author Kohsuke Kawaguchi
 */
public class VirtualMachine {
    public State state;
    public String id;

    public JSONObject json;

    public static class State {
        // TODO: json-lib doesn't seem to support Enum
        // public StateId id;
        public String id;
        public String message;
        public String stackTrace;

        public StateId getId() {
            return StateId.valueOf(id);
        }

        @Override
        public String toString() {
            return "State[id="+id+",message="+message+",stackTrace="+stackTrace;
        }
    }

    public enum StateId {
        config, booting, running, error
    }
}
