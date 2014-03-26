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

package com.cloudbees.jenkins.plugins.mtslavescloud.cli;

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;
import hudson.Extension;
import hudson.Util;
import hudson.cli.CLICommand;
import jenkins.model.Jenkins;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.net.URL;

import static jenkins.model.Jenkins.*;

/**
 * Administrative command to configure this
 *
 * @author Kohsuke Kawaguchi
 */
@Extension
public class InstallMansionCommand extends CLICommand {

    @Argument(metaVar="BROKER_URL",usage="Broker URL")
    public URL url;

    @Option(name="-account",usage="Specify the account if the instance has multiple credentials configured with it")
    public String account;

    @Override
    public String getShortDescription() {
        return "Configures a mansion";
    }

    @Override
    protected int run() throws Exception {
        Jenkins j = Jenkins.getInstance();
        j.checkPermission(ADMINISTER);

        for (MansionCloud c : Util.filter(j.clouds, MansionCloud.class)) {
            if (c.getBroker().equals(url)) {
                stderr.println("Mansion already exists with the URL " +url);
                return 0;
            }
        }

        j.clouds.add(new MansionCloud(url,account,null));
        return 0;
    }
}
