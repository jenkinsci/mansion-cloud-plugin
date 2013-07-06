package com.cloudbees.jenkins.plugins.mtslavescloud.client

import net.sf.json.JSONObject
import org.junit.Test

import com.cloudbees.mtslaves.client.*;

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
        def b = new BrokerRef(new URL("http://localhost:8090/lxc/"));
        VirtualMachineRef vm = b.createVirtualMachine(new HardwareSpec("small"), "88e7313d64af5ee654525625885be2781eb9bae0");
        vm.bootSync();
    }
}
