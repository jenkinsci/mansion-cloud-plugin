package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import net.sf.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public class BrokerRef extends RemoteReference {
    public BrokerRef(URL url) {
        super(url);
    }

    public VirtualMachineRef createVirtualMachine(JSONObject json) throws IOException {
        HttpURLConnection con = postJson(open("createVirtualMachine"), json);
        verifyResponseStatus(con);
        return new VirtualMachineRef(new URL(con.getHeaderField("Location")));
    }
}
