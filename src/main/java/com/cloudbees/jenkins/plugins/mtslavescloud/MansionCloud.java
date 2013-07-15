package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUser;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
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
import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.signature.DSAPrivateKey;
import com.trilead.ssh2.signature.DSAPublicKey;
import com.trilead.ssh2.signature.DSASHA1Verify;
import com.trilead.ssh2.signature.RSAPrivateKey;
import com.trilead.ssh2.signature.RSAPublicKey;
import com.trilead.ssh2.signature.RSASHA1Verify;
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
import jenkins.model.Jenkins;
import org.apache.commons.codec.binary.Base64;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Kohsuke Kawaguchi
 */
public class MansionCloud extends AbstractCloudImpl {
    private final URL broker;

    public SnapshotRef lastSnapshot = null;

    /**
     * List of {@link MansionCloudProperty}s configured for this project.
     */
    @CopyOnWrite
    private volatile DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor> properties
            = new DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor>(Jenkins.getInstance());

    @DataBoundConstructor
    public MansionCloud(URL broker, List<MansionCloudProperty> properties) throws IOException {
        super("mansion"+ Util.getDigestOf(broker.toExternalForm()).substring(0,8), "0"/*unused*/);
        this.broker = broker;
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

    @Override
    public boolean canProvision(Label label) {
        // TODO: we'll likely support multiple kinds of slaves like EC2 plugin does in the future
        return true;
    }

    @Override
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.fine("Provisioning "+label+" workload="+excessWorkload);

        List<PlannedNode> r = new ArrayList<PlannedNode>();
        try {
            for (int i=0; i<excessWorkload; i++) {
                VirtualMachineSpec spec = new VirtualMachineSpec();
                for (MansionVmConfigurator configurator : MansionVmConfigurator.all()) {
                    configurator.configure(this,label,spec);
                }
                spec.network("jenkins");
                if (lastSnapshot == null) {
                    spec.fs(new URL("http://localhost:8080/zfs/f17-base"), "/");
                } else {
                    spec.fs(lastSnapshot.url,"/");
                }

                // we need an SSH key pair to securely login to the allocated slave, but it does't matter what key to use.
                // so just reuse the Jenkins instance identity for a convenience, since this key is readily available,
                // and its private key is hidden to the master.
                InstanceIdentity id = InstanceIdentity.get();
                String publicKey = encodePublicKey(id);

                final SSHUser sshCred = new BasicSSHUserPrivateKey(null,null, JENKINS_USER,
                        new DirectEntryPrivateKeySource(encodePrivateKey(id)),null,null);

                spec.sshd(JENKINS_USER, publicKey.trim());
                final VirtualMachineRef vm;

    		    //TODO : allow user to configure oauth token from credentials plugin
                String oauthToken = "88e7313d64af5ee654525625885be2781eb9bae0";
                HardwareSpec box = new HardwareSpec("small");

                if (MANSION_SECRET==null)
                    vm = new BrokerRef(broker).createVirtualMachine(box, oauthToken);
                else
                    vm = new MansionRef(broker,MANSION_SECRET).createVirtualMachine(box, oauthToken);

                LOGGER.fine("Allocated "+vm.url);
                try {
                    vm.setup(spec);
                } catch (VirtualMachineConfigurationException e) {
                    //TODO: retry with another snapshot
                    LOGGER.log(Level.WARNING, "Failed to configure VM",e);
                }

                Future<Node> f = Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                    public Node call() throws Exception {
                        vm.bootSync();
                        LOGGER.fine("Booted " + vm.url);
                        SshdEndpointProperty sshd = vm.getState().getProperty(SshdEndpointProperty.class);
                        SSHLauncher launcher = new SSHLauncher(
                                sshd.getHost(), sshd.getPort(), sshCred, null, null, null, null);
                        MansionSlave s = new MansionSlave(vm, launcher);

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
            LOGGER.log(Level.WARNING, "Failed to provision from "+this,e);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Failed to provision from " + this, e);
        }
        return r;
    }

    // TODO: move this to instance-identity-module
    private String encodePrivateKey(InstanceIdentity id) {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("-----BEGIN RSA PRIVATE KEY-----\n");
            buf.append(new String(Base64.encodeBase64(id.getPrivate().getEncoded()),"US-ASCII")).append("\n");
            buf.append("-----END RSA PRIVATE KEY-----\n");
            return buf.toString();
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // US-ASCII is mandatory
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
}
