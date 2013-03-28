package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import com.cloudbees.jenkins.plugins.mtslavescloud.client.VirtualMachine.State;
import hudson.util.IOUtils;
import net.sf.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public class VirtualMachineRef extends RemoteReference {

    public VirtualMachineRef(URL url) {
        super(url);
    }

    public String getId() {
        String p = url.getPath();
        return p.substring(p.lastIndexOf('/'+1));
    }

    /**
     * Synchronously boot and wait until its fully booted.
     */
    public void boot() throws IOException, InterruptedException {
        HttpURLConnection con = open("boot");
        con.setRequestMethod("POST");
        con.connect();
        IOUtils.drain(con.getInputStream());
        verifyResponseStatus(con);

        for (int i=0; i< TIMEOUT; i++) {
            Thread.sleep(1000);
            VirtualMachine state = getState();
            switch (state.state.getId()) {
            default:
                throw new IllegalStateException(state.state.getId().toString());
            case booting:
                continue;   // wait some more
            case running:
                return;
            case error:
                throw new IllegalStateException("Virtual machine failed to boot: "+state.json.toString());
            }
        }

        throw new IOException("Time out: VM is taking forever to boot"+url);
    }

    private VirtualMachine getState() throws IOException {
        HttpURLConnection con = open(".");
        JSONObject json = JSONObject.fromObject(IOUtils.toString(con.getInputStream(), "UTF-8"));
        VirtualMachine vm = (VirtualMachine) json.toBean(VirtualMachine.class);
        vm.json = json;
        return vm;
    }

    public static final int TIMEOUT = 60; /* secs */
}
