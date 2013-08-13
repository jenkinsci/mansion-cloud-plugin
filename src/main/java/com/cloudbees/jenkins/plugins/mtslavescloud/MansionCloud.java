package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.EndPoints;
import com.cloudbees.api.BeesClient;
import com.cloudbees.api.cr.Capability;
import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.api.oauth.TokenRequest;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUser;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.DirectEntryPrivateKeySource;
import com.cloudbees.mtslaves.client.BrokerRef;
import com.cloudbees.mtslaves.client.HardwareSpec;
import com.cloudbees.mtslaves.client.MansionRef;
import com.cloudbees.mtslaves.client.SnapshotRef;
import com.cloudbees.mtslaves.client.VirtualMachineConfigurationException;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import com.cloudbees.mtslaves.client.properties.SshdEndpointProperty;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesAccount;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesUser;
import com.trilead.ssh2.signature.RSAPublicKey;
import com.trilead.ssh2.signature.RSASHA1Verify;
import hudson.AbortException;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.bouncycastle.openssl.PEMWriter;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.StringWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * {@link Cloud} implementation that talks to CloudBees' multi-tenant slaves.
 *
 * @author Kohsuke Kawaguchi
 */
public class MansionCloud extends AbstractCloudImpl {
    private final URL broker;

    /**
     * A {@link CloudBeesUser} can have access to multiple accounts,
     * so this field would specify which account is used to request slaves.
     */
    private String account;

    public SnapshotRef lastSnapshot = null;

    /**
     * List of {@link MansionCloudProperty}s configured for this project.
     */
    @CopyOnWrite
    private volatile DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor> properties
            = new DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor>(Jenkins.getInstance());

    @DataBoundConstructor
    public MansionCloud(URL broker, String account, List<MansionCloudProperty> properties) throws IOException {
        super("mansion"+ Util.getDigestOf(broker.toExternalForm()).substring(0,8), "0"/*unused*/);
        this.broker = broker;
        this.account = account;
        if (properties!=null)
            this.properties.replaceBy(properties);
    }

    public DescribableList<MansionCloudProperty, MansionCloudPropertyDescriptor> getProperties() {
        return properties;
    }

    /**
     * End point that we talk to.
     */
    public URL getBroker() {
        return broker;
    }

    public String getAccount() {
        return account;
    }

    @Override
    public boolean canProvision(Label label) {
        return resolveToTemplate(label)!=null;
    }

    /**
     * Maps a label to a template.
     *
     * This is a scaffolding for time being as the mapping to label to {@link SlaveTemplate}
     * should be configured in each instance (with a reasonable defaulting to hide that complexity
     * for those who don't care.)
     */
    private SlaveTemplate resolveToTemplate(Label label) {
        try {
            Map<String,SlaveTemplate> m = SlaveTemplate.load(this.getClass().getResourceAsStream("machines.json"));
            SlaveTemplate s = m.get(label.toString());
            if (s!=null)    return s;

            // until we tidy up the template part, fall back to LXC as the default so as not to block Ryan
            return m.get("lxc-fedora17");
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public String createAccessToken(URL broker) throws AbortException, OauthClientException {
        CloudBeesUser u = getDescriptor().findUser();
        BeesClient bees = new BeesClient(EndPoints.runAPI(),u.getAPIKey(), Secret.toString(u.getAPISecret()), null, null);

        CloudBeesAccount acc = u.getAccount(Util.fixNull(account));
        if (acc==null)      acc = u.getAccounts().get(0); // fallback

        TokenRequest tr = new TokenRequest()
            .withAccountName(acc.getName())
            .withScope(broker, PROVISION_CAPABILITY)
            .withGenerateRequestToken(false);
        return bees.getOauthClient().createToken(tr).accessToken;
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.fine("Provisioning "+label+" workload="+excessWorkload);

        final SlaveTemplate st = resolveToTemplate(label);

        List<PlannedNode> r = new ArrayList<PlannedNode>();
        try {
            for (int i=0; i<excessWorkload; i++) {
                VirtualMachineSpec spec = new VirtualMachineSpec();
                for (MansionVmConfigurator configurator : MansionVmConfigurator.all()) {
                    configurator.configure(this,label,spec);
                }
                st.populate(spec);

                st.loadClan().applyTo(spec);    // if we have more up-to-date snapshots, use them

                // we need an SSH key pair to securely login to the allocated slave, but it does't matter what key to use.
                // so just reuse the Jenkins instance identity for a convenience, since this key is readily available,
                // and its private key is hidden to the master.
                InstanceIdentity id = InstanceIdentity.get();
                String publicKey = encodePublicKey(id);

                final SSHUser sshCred = new BasicSSHUserPrivateKey(null,null, JENKINS_USER,
                        new DirectEntryPrivateKeySource(encodePrivateKey(id)),null,null);

                spec.sshd(JENKINS_USER, 15000, publicKey.trim()); // TODO: should UID be configurable?
                final VirtualMachineRef vm;

                HardwareSpec box = new HardwareSpec("small");

                URL broker = new URL(this.broker,"/"+st.mansion+"/");
                if (MANSION_SECRET==null) {
                    String oauthToken = createAccessToken(broker);
                    vm = new BrokerRef(broker).createVirtualMachine(box, oauthToken);
                } else {
                    vm = new MansionRef(broker,MANSION_SECRET).createVirtualMachine(box, "unused");
                }

                LOGGER.fine("Allocated "+vm.url);
                try {
                    vm.setup(spec);
                } catch (VirtualMachineConfigurationException e) {
                    //TODO: retry with another snapshot
                    LOGGER.log(WARNING, "Failed to configure VM",e);
                }

                Future<Node> f = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        vm.bootSync();
                        LOGGER.fine("Booted " + vm.url);
                        SshdEndpointProperty sshd = vm.getState().getProperty(SshdEndpointProperty.class);
                        SSHLauncher launcher = new SSHLauncher(
                                // Linux slaves can run without it, but OS X slaves need java.awt.headless=true
                                sshd.getHost(), sshd.getPort(), sshCred, "-Djava.awt.headless=true", null, null, null);
                        MansionSlave s = new MansionSlave(vm,st,launcher);

                        try {
                            // connect before we declare victory
                            // If we declare
                            // the provisioning complete by returning without the connect
                            // operation, NodeProvisioner may decide that it still wants
                            // one more instance, because it sees that (1) all the slaves
                            // are offline (because it's still being launched) and
                            // (2) there's no capacity provisioned yet.
                            //
                            // deferring the completion of provisioning until the launch
                            // goes successful prevents this problem.
                            Jenkins.getInstance().addNode(s);
                            for (int tries = 0; tries < 10; tries ++) {
                                Thread.sleep(500);
                                try {
                                    s.toComputer().connect(false).get();
                                    break;
                                } catch (ExecutionException e) {
                                    if (! (e.getCause() instanceof IOException))
                                        throw e;
                                }
                            }
                        } finally {
                            s.cancelHoldOff();
                        }

                        return s;
                    }
                });
                r.add(new PlannedNode(vm.getId(),f,1));
            }
        } catch (IOException e) {
            LOGGER.log(WARNING, "Failed to provision from "+this,e);
        } catch (InterruptedException e) {
            LOGGER.log(WARNING, "Failed to provision from " + this, e);
        } catch (OauthClientException e) {
            LOGGER.log(WARNING, "Failed to provision from " + this, e);
        }
        return r;
    }

    // TODO: move this to instance-identity-module
    private String encodePrivateKey(InstanceIdentity id) {
        try {
            StringWriter sw = new StringWriter();
            PEMWriter pem = new PEMWriter(sw);
            pem.writeObject(id.getPrivate());
            pem.close();
            return sw.toString();
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    // TODO: move this to instance-identity module
    private String encodePublicKey(InstanceIdentity id) throws IOException {
        java.security.interfaces.RSAPublicKey key = id.getPublic();
        return "ssh-rsa " + hudson.remoting.Base64.encode(RSASHA1Verify.encodeSSHRSAPublicKey(new RSAPublicKey(key.getPublicExponent(),key.getModulus())));
    }

    public static MansionCloud get() {
        return Jenkins.getInstance().clouds.get(MansionCloud.class);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Multi-tenancy Slave Cloud";
        }

        private CloudBeesUser findUser() throws AbortException {
            // TODO: perhaps we should also let the user configure which credential to use?
            for (CloudBeesUser user : CredentialsProvider.lookupCredentials(CloudBeesUser.class)) {
                return user;
            }
            throw new AbortException("No cloudbees account is registered with this Jenkins instance.");
        }

        public ListBoxModel doFillAccountItems() throws AbortException {
            CloudBeesUser user = findUser();
            ListBoxModel r = new ListBoxModel();
            for (CloudBeesAccount acc : user.getAccounts()) {
                r.add(acc.getDisplayName()+" ("+acc.getName()+")", acc.getName());
            }
            return r;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(MansionCloud.class.getName());

    /**
     * UNIX user name to be created inside the slave to be used for build.
     */
    public static final String JENKINS_USER = "jenkins";

    /**
     * This property can be set to the secret key of the mansion to let this plugin talk directly to
     * a mansion without going through a broker. Useful during development.
     */
    public static String MANSION_SECRET = System.getProperty("mansion.secret");

    // TODO: move to the mt-slaves-client
    public static Capability PROVISION_CAPABILITY = new Capability("https://types.cloudbees.com/broker/provision");

    /**
     * Are we running inside DEV@cloud?
     */
    public static boolean isInDevAtCloud() {
        return Jenkins.getInstance().getPlugin("cloudbees-account")!=null;
    }
}
