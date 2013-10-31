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
