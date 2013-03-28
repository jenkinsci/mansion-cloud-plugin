package com.cloudbees.jenkins.plugins.mtslavescloud.client

import net.sf.json.JSONObject
import org.junit.Test

/**
 *
 *
 * @author Kohsuke Kawaguchi
 */
class VirtualMachineSpecTest {
    @Test
    void serialization() {
        def spec = new VirtualMachineSpec();
        spec.configs << 5;
        spec.configs << JSONObject.fromObject('{ "x":"y"} ');
        def s = JSONObject.fromObject(spec).toString()
        println s
        assert '{"configs":[5,{"x":"y"}]}'==s
    }

    @Test
    void testBroker() {
        def b = new BrokerRef(new URL("http://localhost:8080/dummy-vm/"));
        VirtualMachineRef vm = b.createVirtualMachine(new VirtualMachineSpec());
        vm.boot();
    }
}
