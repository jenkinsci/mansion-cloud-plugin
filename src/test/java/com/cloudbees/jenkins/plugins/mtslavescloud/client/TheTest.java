package com.cloudbees.jenkins.plugins.mtslavescloud.client;

import net.sf.json.JSONObject;
import org.junit.Test;

import java.net.URL;

/**
 * @author Kohsuke Kawaguchi
 */
public class TheTest {
    @Test
    public void testBroker() throws Exception {
        BrokerRef b = new BrokerRef(new URL("http://localhost:8080/dummy-vm/"));
        VirtualMachineRef vm = b.createVirtualMachine(JSONObject.fromObject("{\"configs\":[]}"));
        vm.boot();


    }
}
