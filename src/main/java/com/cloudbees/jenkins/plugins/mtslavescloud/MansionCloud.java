package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.EndPoints;
import com.cloudbees.api.BeesClient;
import com.cloudbees.api.TokenGenerator;
import com.cloudbees.api.cr.Capability;
import com.cloudbees.api.cr.Credential;
import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.api.oauth.TokenRequest;
import com.cloudbees.jenkins.plugins.mtslavescloud.util.BackOffCounter;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.DirectEntryPrivateKeySource;
import com.cloudbees.mtslaves.client.BrokerRef;
import com.cloudbees.mtslaves.client.HardwareSpec;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
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

    private transient BackOffCounter backoffCounter;

    /**
     * So long as {@link CloudBeesUser} doesn't change, we'll reuse the same {@link TokenGenerator}
     */
    private transient volatile Cache tokenGenerator;

    class Cache {
        private final TokenGenerator tokenGenerator;
        private final CloudBeesUser user;

        Cache(CloudBeesUser u) {
            this.user = u;
            BeesClient bees = new BeesClient(EndPoints.runAPI(),u.getAPIKey(), Secret.toString(u.getAPISecret()), null, null);
            tokenGenerator = TokenGenerator.from(bees).withCache();
        }

        Credential obtain(TokenRequest tr) throws OauthClientException {
            return tokenGenerator.createToken(tr).asCredential();
        }
    }

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
        initTransient();
    }

    private void initTransient() {
        backoffCounter = new BackOffCounter(2,MAX_BACKOFF_SECONDS, TimeUnit.SECONDS);
    }

    protected Object readResolve() {
        initTransient();
        return this;
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

    /**
     * Determines which labels the {@NodeProvisioner will request this Cloud to provision.
     *
     * @param label
     * @return true if the label is a valid template
     */
    @Override
    public boolean canProvision(Label label) {
        try {
            Map<String,SlaveTemplate> m = SlaveTemplate.load(this.getClass().getResourceAsStream("machines.json"));

            return label == null || m.get(label.toString()) != null || label.toString().startsWith("m1.");
        } catch (IOException e) {
            throw new Error(e);
        }

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
            for (SlaveTemplate t : m.values()) {
                t.postInit(this);
            }

            if (label != null) {
                //trim off anything after the last '.' since that optionally contains the size
                int imageEnd = label.toString().contains(".") ? label.toString().indexOf(".") : label.toString().length();
                String image = label.toString().substring(0,imageEnd);
                SlaveTemplate s = m.get(image);
                if (s!=null)    return s;
            }

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

    public Credential createAccessToken(URL broker) throws AbortException, OauthClientException {
        CloudBeesUser u = getDescriptor().findUser();
        CloudBeesAccount acc = u.getAccount(Util.fixNull(account));
        if (acc==null)      acc = u.getAccounts().get(0); // fallback

        TokenRequest tr = new TokenRequest()
            .withAccountName(acc.getName())
            .withScope(broker, PROVISION_CAPABILITY)
            .withGenerateRequestToken(false);

        if (tokenGenerator==null || tokenGenerator.user!=u)
            tokenGenerator = new Cache(u);
        return tokenGenerator.obtain(tr);
    }

    @Override
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        if (backoffCounter.shouldBackOff()) {
            return Collections.emptyList();
        }

        LOGGER.fine("Provisioning "+label+" workload="+excessWorkload);


        List<PlannedNode> r = new ArrayList<PlannedNode>();
        try {
            for (int i=0; i<excessWorkload; i++) {
                final SlaveTemplate st = resolveToTemplate(label);
                VirtualMachineSpec spec = new VirtualMachineSpec();
                for (MansionVmConfigurator configurator : MansionVmConfigurator.all()) {
                    configurator.configure(this,label,spec);
                }
                st.populate(spec);


                // we need an SSH key pair to securely login to the allocated slave, but it does't matter what key to use.
                // so just reuse the Jenkins instance identity for a convenience, since this key is readily available,
                // and its private key is hidden to the master.
                InstanceIdentity id = InstanceIdentity.get();
                String publicKey = encodePublicKey(id);

                final SSHUserPrivateKey sshCred = new BasicSSHUserPrivateKey(null,null, JENKINS_USER,
                        new DirectEntryPrivateKeySource(encodePrivateKey(id)),null,null);

                spec.sshd(JENKINS_USER, 15000, publicKey.trim()); // TODO: should UID be configurable?

                HardwareSpec box;
                if (label != null && label.toString().contains(".")) {
                    box = new HardwareSpec(label.toString().substring(label.toString().lastIndexOf(".") + 1));
                } else {
                    box = new HardwareSpec("small");
                }

                URL broker = new URL(this.broker,"/"+st.mansion+"/");
                final VirtualMachineRef vm = new BrokerRef(broker,createAccessToken(broker)).createVirtualMachine(box);

                LOGGER.fine("Allocated "+vm.url);
                try {
                    VirtualMachineSpec specWithSnapshots = spec.clone();
                    FileSystemClan fileSystemClan = st.loadClan();
                    fileSystemClan.applyTo(specWithSnapshots);    // if we have more up-to-date snapshots, use them
                    vm.setup(specWithSnapshots);
                } catch (VirtualMachineConfigurationException e) {
                    LOGGER.log(WARNING, "Couldn't find snapshot, trying with originals",e);
                    //TODO: we should try to figure out which snapshot to revert
                    //TODO: instead of reverting them all
                    try {
                        vm.setup(spec);
                    } catch (VirtualMachineConfigurationException e2) {
                        LOGGER.log(SEVERE, "Failed to configure VM",e2);
                    }
                }

                Future<Node> f = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        vm.bootSync();
                        LOGGER.fine("Booted " + vm.url);
                        SshdEndpointProperty sshd = vm.getState().getProperty(SshdEndpointProperty.class);
                        SSHLauncher launcher = new SSHLauncher(
                                // Linux slaves can run without it, but OS X slaves need java.awt.headless=true
                                sshd.getHost(), sshd.getPort(), sshCred, "-Djava.awt.headless=true", null, null, null);
                        MansionSlave s = new MansionSlave(vm,st,label,launcher);

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
                        if (s.toComputer().isOffline()) {
                            // if we can't connect, backoff before the next try
                            MansionCloud.this.backoffCounter.recordError();
                            LOGGER.log(Level.WARNING,"Failed to connect to slave over ssh, will try again in {0} seconds", backoffCounter.getBackOff());
                        } else {
                            // success!
                            MansionCloud.this.backoffCounter.clear();
                        }
                        return s;
                    }
                });
                r.add(new PlannedNode(vm.getId(),f,1));
            }
        } catch (IOException e) {
            backoffCounter.recordError();
            LOGGER.log(WARNING, "Failed to provision from "+this,e);
            LOGGER.log(WARNING,"Will try again in {0} seconds.", backoffCounter.getBackOff());
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

    // TODO: move to the mt-slaves-client
    public static Capability PROVISION_CAPABILITY = new Capability("https://types.cloudbees.com/broker/provision");

    /**
     * Are we running inside DEV@cloud?
     */
    public static boolean isInDevAtCloud() {
        return Jenkins.getInstance().getPlugin("cloudbees-account")!=null;
    }
    /**
     * The maximum number of seconds to back off from provisioning if
     * we continuously have problems provisioning or launching slaves.
     */
    public static Long MAX_BACKOFF_SECONDS = Long.getLong(MansionCloud.class.getName() + ".maxBackOffSeconds", 600);  // 5 minutes
}
