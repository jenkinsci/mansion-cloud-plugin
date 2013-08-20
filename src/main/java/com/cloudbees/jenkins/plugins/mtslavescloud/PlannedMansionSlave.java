package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.hudson.plugins.Config;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.FileSystemClan;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.jenkins.plugins.mtslavescloud.util.PromisedFuture;
import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey.DirectEntryPrivateKeySource;
import com.cloudbees.mtslaves.client.VirtualMachineConfigurationException;
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.mtslaves.client.VirtualMachineSpec;
import com.cloudbees.mtslaves.client.properties.SshdEndpointProperty;
import com.trilead.ssh2.signature.RSAPublicKey;
import com.trilead.ssh2.signature.RSASHA1Verify;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.HttpResponses;
import hudson.util.IOException2;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.acegisecurity.GrantedAuthority;
import org.bouncycastle.openssl.PEMWriter;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * Represents the activity of acquiring a slave from mtslaves cloud.
 *
 * @author Kohsuke Kawaguchi
 */
public class PlannedMansionSlave extends PlannedNode implements Callable<Node> {
    /**
     * Cloud that this slave is being provisioned from.
     */
    private final MansionCloud cloud;

    /**
     * Virtual machine on the mtslaves cloud that we are making a slave out of.
     */
    private final VirtualMachineRef vm;

    /**
     * Templates that this slave is instantiating.
     */
    private final SlaveTemplate st;

    /**
     * Label for which this slave is allocated.
     */
    private final Label label;

    /**
     * Set to the timestamp when this {@link PlannedNode} is marked as spent
     *
     * @see #spent()
     */
    private volatile long spent = 0;

    /**
     * If the provisioning fails, record that problem here.
     */
    private volatile Throwable problem;

    /**
     * One line status of what we are doing.
     */
    private String status; // TODO: change to Localizable for i18n

    private volatile boolean dismissed;


    public PlannedMansionSlave(Label label, SlaveTemplate template, VirtualMachineRef vm) {
        super(vm.getId(), new PromisedFuture<Node>(), 1);
        this.st = template;
        this.cloud = template.getMansion();
        this.vm = vm;
        this.label = label;

        cloud.getInProgressSet().onStarted(this);

        status = "Allocated "+displayName;
        // start allocation
        promise().setBase(Computer.threadPoolForRemoting.submit(this));
    }

    /**
     * Returns the path this this object in the URL space relative to the context path
     */
    public String getUrl() {
        return "cloud/"+cloud.name+"/inProgressSet/"+displayName;
    }

    /**
     * This is our promise to the client of {@link PlannedNode}
     */
    protected PromisedFuture<Node> promise() {
        return (PromisedFuture<Node>)super.future;
    }

    /**
     * If this allocation has failed with a problem, report that.
     */
    public Throwable getProblem() {
        return problem;
    }

    public long getProblemTimestamp() {
        return spent;
    }

    public VirtualMachineRef getVm() {
        return vm;
    }

    public String getStatus() {
        return status;
    }

    /**
     * Gets the type of the mansion
     */
    public String getMansionType() {
        return st.getMansionType();
    }

    /**
     * This method synchronously acquires and sets up the slave.
     *
     * When this method returns we have a connected slave.
     */
    public Node call() throws Exception {
        status = "Configuring";

        final VirtualMachineSpec spec = new VirtualMachineSpec();
        for (MansionVmConfigurator configurator : MansionVmConfigurator.all()) {
            configurator.configure(cloud,label,spec);
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
        try {
            VirtualMachineSpec specWithSnapshots = spec.clone();
            FileSystemClan fileSystemClan = st.getClan();
            fileSystemClan.applyTo(specWithSnapshots);    // if we have more up-to-date snapshots, use them
            vm.setup(specWithSnapshots);
        } catch (VirtualMachineConfigurationException e) {
            LOGGER.log(WARNING, "Couldn't find snapshot, trying with originals",e);
            //TODO: we should try to figure out which snapshot to revert
            //TODO: instead of reverting them all
            try {
                vm.setup(spec);
            } catch (VirtualMachineConfigurationException e2) {
                throw new IOException2("Failed to configure VM", e2);
            }
        }

        if (INJECT_FAULT)
            throw new IllegalStateException("Injected failure");

        status = "Booting";
        vm.bootSync();
        LOGGER.fine("Booted " + vm.url);

        status = "Connecting";
        SshdEndpointProperty sshd = vm.getState().getProperty(SshdEndpointProperty.class);
        SSHLauncher launcher = new SSHLauncher(
                // Linux slaves can run without it, but OS X slaves need java.awt.headless=true
                sshd.getHost(), sshd.getPort(), sshCred, "-Djava.awt.headless=true", null, null, null);
        MansionSlave s = new MansionSlave(vm,st,label,launcher);
        IOException lastConnectionException = null;
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
            for (int tries = 1; tries <= 10; tries ++) {
                if (tries>1)
                    status = "Connecting #"+tries;
                Thread.sleep(500);
                try {
                    s.toComputer().connect(false).get();
                    break;
                } catch (ExecutionException e) {
                    if (! (e.getCause() instanceof IOException))
                        throw e;
                    else
                        lastConnectionException = (IOException) e.getCause();
                }
            }
        } finally {
            s.cancelHoldOff();
        }

        if (s.toComputer().isOffline()) {
            // if we can't connect, backoff before the next try
            throw new IOException2("Failed to connect to slave over ssh", lastConnectionException);
        }

        status = "Online";
        return s;
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
        return "ssh-rsa " + hudson.remoting.Base64.encode(RSASHA1Verify.encodeSSHRSAPublicKey(new RSAPublicKey(key.getPublicExponent(), key.getModulus())));
    }

    @Override
    public void spent() {
        spent = System.currentTimeMillis();

        // how did the provisioning go?
        try {
            future.get(0,TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            problem = e.getCause();
            if (problem==null)  // this shouldn't happen but let's be defensive
                problem = e;
        } catch (InterruptedException e) {// this shouldn't happen but let's be defensive
            problem = e;
        } catch (TimeoutException e) {// this shouldn't happen but let's be defensive
            problem = e;
        }

        if (problem!=null) {
            status = "Failed";
            cloud.getBackOffCounter(st).recordError();
        } else {
            status = "Completed";
        }

        cloud.getInProgressSet().update();
    }

    @RequirePOST
    public HttpResponse doDismiss() {
        cloud.checkPermission(Jenkins.ADMINISTER);
        dismissed = true;
        cloud.getInProgressSet().update();
        return HttpResponses.redirectToContextRoot();
    }

    /**
     * Is this {@link PlannedMansionSlave} interesting enough to show in the management/monitoring UI?
     */
    protected boolean isNoteWorthy() {
        if (spent==0)     return true;    // in progress
        if (problem!=null && System.currentTimeMillis() < spent+PROBLEM_RETENTION_SPAN && !dismissed)
            return true;    // recent enough failure
        return false;
    }

    /**
     * Should we hyperlink to the cloud slave URL?
     */
    public boolean shouldHyperlinkSlave() {
        for (GrantedAuthority a : Jenkins.getAuthentication().getAuthorities())
            if (a.getAuthority().equals("cloudbees-admin"))
                return true;
        return Config.isDevMode();
    }

    /**
     * UNIX user name to be created inside the slave to be used for build.
     */
    public static final String JENKINS_USER = "jenkins";

    private static final Logger LOGGER = Logger.getLogger(PlannedMansionSlave.class.getName());

    /**
     * In case of a failure, how long do we retain it, in milliseconds? Default is 4 hours.
     */
    public static long PROBLEM_RETENTION_SPAN = Long.getLong(PlannedMansionSlave.class.getName()+".problemRetention", TimeUnit2.HOURS.toMillis(4));

    /**
     * Debug switch to inject a fault in slave allocation to test error handling.
     */
    public static boolean INJECT_FAULT = false;
}
