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

package com.cloudbees.jenkins.plugins.mtslavescloud.client

import net.sf.json.JSONObject
import org.junit.Ignore
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
    @Ignore
    void testBroker() {
        def b = new BrokerRef(new URL("http://localhost:8090/lxc/"));
        VirtualMachineRef vm = b.createVirtualMachine(new HardwareSpec("small"), "88e7313d64af5ee654525625885be2781eb9bae0");
        vm.bootSync();
    }
}
