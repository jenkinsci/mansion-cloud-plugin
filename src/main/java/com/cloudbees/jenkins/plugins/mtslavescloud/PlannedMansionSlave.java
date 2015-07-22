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

package com.cloudbees.jenkins.plugins.mtslavescloud;

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
import hudson.Extension;
import hudson.Main;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.ComputerListener;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.HttpResponses;
import hudson.util.IOException2;
import hudson.util.TimeUnit2;
import jenkins.model.Jenkins;
import org.acegisecurity.GrantedAuthority;
import org.bouncycastle.openssl.PEMWriter;
import org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement;
import org.jenkinsci.main.modules.instance_identity.InstanceIdentity;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
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
    private VirtualMachineRef vm;

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

    /**
     * When did we start provisioning this guy?
     */
    public final long startTime = System.currentTimeMillis();
    
    /**
     * The node that was provisioned.
     */
    public volatile MansionSlave node;

    public PlannedMansionSlave(Label label, SlaveTemplate template) {
        super(template.getDisplayName(), new PromisedFuture<Node>(), 1);
        this.st = template;
        this.cloud = template.getMansion();
        this.label = label;

        cloud.getInProgressSet().onStarted(this);

        status = "Requesting";
    }

    public String getDisplayName() {
        return vm == null ? displayName : vm.getId();
    }
    
    public boolean isProvisioning() {
        return spent == 0 && vm == null;
    }
    
    /**
     * Returns the path this this object in the URL space relative to the context path
     */
    public String getUrl() {
        return "cloud/"+cloud.name+"/inProgressSet/"+getDisplayName();
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

    public void onVirtualMachineProvisioned(@Nonnull VirtualMachineRef vm) {
        vm.getClass(); // throw NPE if null
        if (this.vm == null) {
            this.vm = vm;
            // start allocation
            promise().setBase(Computer.threadPoolForRemoting.submit(this));
        } else {
            throw new IllegalStateException("VirtualMachineRef already allocated");
        }
        
    }
    
    public void onProvisioningFailure(final Throwable e) {
        promise().setBase(new Future<Node>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return false;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public Node get() throws InterruptedException, ExecutionException {
                throw new ExecutionException(e);
            }

            @Override
            public Node get(long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                throw new ExecutionException(e);
            }
        });
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
    @IgnoreJRERequirement
    public Node call() throws Exception {
        Thread t = Thread.currentThread();

        final String oldName = t.getName();
        final ClassLoader oldCL = t.getContextClassLoader();
        t.setContextClassLoader(getClass().getClassLoader());
        try {
            t.setName(oldName + " : allocated " + vm.url);
            status = "Allocated "+vm.getId();
            LOGGER.log(Level.FINE, "Allocated {0}", vm.url);

            status = "Configuring";

            t.setName(oldName + " : configuring " + vm.url);

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
                fileSystemClan.applyTo(specWithSnapshots, vm);    // if we have more up-to-date snapshots, use them
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

            t.setName(oldName + " : booting " + vm.url);

            status = "Booting";
            vm.bootSync();
            LOGGER.fine("Booted " + vm.url);

            status = "Provisioned";
            SshdEndpointProperty sshd = vm.getState().getProperty(SshdEndpointProperty.class);
            SSHLauncher launcher = new SSHLauncher(sshd.getHost(), sshd.getPort(), sshCred,
                    // Linux slaves can run without it, but OS X slaves need java.awt.headless=true
                    "-Djava.awt.headless=true", null, null, null, null, 180, 10, 1);
            return node = new MansionSlave(vm,st,label,launcher);
        } finally {
            t.setName(oldName);
            t.setContextClassLoader(oldCL);
        }
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

    /*package*/ void renewLease() throws IOException {
        if (problem == null) {
            vm.renew();
            LOGGER.fine("Renewed a lease of " + vm.url);
        }
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
            MansionSlave node = this.node;
            if (node != null && node.getChannel() != null) {
                status = "Completed";
            }
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
        if (vm == null) return false; // never allocated
        if (problem!=null && System.currentTimeMillis() < spent+PROBLEM_RETENTION_SPAN && !dismissed)
            return true;    // recent enough failure
        MansionSlave node = this.node;
        if (node != null) {
            final MansionComputer c = node.asComputer();
            return c == null || !c.isInitialConnectionEstablished();
        }
        return false;
    }

    /**
     * Should we hyperlink to the cloud slave URL?
     */
    public boolean shouldHyperlinkSlave() {
        for (GrantedAuthority a : Jenkins.getAuthentication().getAuthorities())
            if (a.getAuthority().equals("cloudbees-admin"))
                return true;
        return Main.isDevelopmentMode || Main.isUnitTest || Boolean.getBoolean("hudson.hpi.run");
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

    public void setStatus(String status) {
        this.status = status;
    }

    public MansionSlave getNode() {
        return node;
    }

    public void onOnline() {
        cloud.getBackOffCounter(st).clear();
        node = null; // no longer interesting
        status = "Online";
        cloud.getInProgressSet().update();
    }
    
    public void onTerminate() {
        node = null;
        cloud.getInProgressSet().update();
    }
    
    public void onConnectFailure(Throwable problem) {
        cloud.getBackOffCounter(st).recordError();
        this.problem = problem;
        status = "Could not connect";
        cloud.getInProgressSet().update();
    }

    @Extension
    public static class ComputerListenerImpl extends ComputerListener {
        @Override
        public void onOnline(Computer c, TaskListener listener) throws IOException, InterruptedException {
            if (c instanceof MansionComputer) {
                MansionSlave s = ((MansionComputer) c).getNode();
                for (MansionCloud cloud: Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
                    for (PlannedMansionSlave p: cloud.getInProgressSet()) {
                        if (p.getNode() == s) {
                            p.onOnline();
                            return;
                        }
                    }
                }
            }
        }
    }
}
