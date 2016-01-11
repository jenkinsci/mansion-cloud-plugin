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

import com.cloudbees.EndPoints;
import com.cloudbees.api.BeesClient;
import com.cloudbees.api.TokenGenerator;
import com.cloudbees.api.cr.Capability;
import com.cloudbees.api.cr.Credential;
import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.api.oauth.TokenRequest;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplateList;
import com.cloudbees.jenkins.plugins.mtslavescloud.util.BackOffCounter;
import com.cloudbees.mtslaves.client.BrokerRef;
import com.cloudbees.mtslaves.client.HardwareSpec;
import com.cloudbees.mtslaves.client.QuotaExceededException;
import com.cloudbees.mtslaves.client.TooManyVirtualMachinesException;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesAccount;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesUser;
import hudson.AbortException;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * {@link Cloud} implementation that talks to CloudBees' multi-tenant slaves.
 *
 * @author Kohsuke Kawaguchi
 */
public class MansionCloud extends AbstractCloudImpl {
    private static final boolean NEED_OVERPROVISIONING_GUARD =
            Jenkins.getVersion() == null || Jenkins.getVersion().isOlderThan(new VersionNumber("1.607"));

    private final URL broker;

    /**
     * A {@link CloudBeesUser} can have access to multiple accounts,
     * so this field would specify which account is used to request slaves.
     */
    private String account;

    /**
     * Which broker is working and which one is not?
     */
    private transient /*almost final*/ ConcurrentMap<String/*broker ID*/,BackOffCounter> backoffCounters;

    private transient /*almost final*/ QuotaProblems quotaProblems;
    /**
     * So long as {@link CloudBeesUser} doesn't change, we'll reuse the same {@link TokenGenerator}
     */
    private transient volatile Cache tokenGenerator;

    /**
     * Keeps track of noteworthy slave allocations.
     *
     * This is the basis for the management UI. This includes all the in-progress allocations that haven't
     * completed, as well as failures that we want to keep around.
     */
    private transient /*almost final*/ PlannedMansionSlaveSet inProgressSet;

    /**
     * Is a call to the broker in progress?
     */
    private transient boolean provisioning = false;

    /**
     * Caches {@link TokenGenerator} by keying it off from {@link CloudBeesUser} that provides its credential.
     */
    class Cache {
        private final TokenGenerator tokenGenerator;
        private final CloudBeesUser user;

        Cache(CloudBeesUser u) {
            this.user = u;
            BeesClient bees = new BeesClient(EndPoints.runAPI(),u.getAPIKey(), Secret.toString(u.getAPISecret()), null, null);
            tokenGenerator = TokenGenerator.from(bees).withCache();
        }

        Credential obtain(TokenRequest tr) throws OauthClientException {
            return tokenGenerator.asCredential(tr);
        }
    }

    /**
     * List of {@link MansionCloudProperty}s configured for this project.
     */
    @CopyOnWrite
    private volatile DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor> properties
            = new DescribableList<MansionCloudProperty,MansionCloudPropertyDescriptor>(Jenkins.getInstance());

    /**
     * Last known failure during provisioning.
     */
    private transient Exception lastException;

    public MansionCloud(URL broker) throws IOException {
        this(broker, null, null);
    }

    @DataBoundConstructor
    public MansionCloud(URL broker, String account, List<MansionCloudProperty> properties) throws IOException {
        super("mansion" + Util.getDigestOf(broker.toExternalForm()).substring(0, 8), "0"/*unused*/);
        this.broker = broker;
        this.account = account;
        if (properties!=null)
            this.properties.replaceBy(properties);
        initTransient();
    }

    private void initTransient() {
        backoffCounters = new ConcurrentHashMap<String, BackOffCounter>();
        quotaProblems = new QuotaProblems();
        inProgressSet = new PlannedMansionSlaveSet();
    }

    protected Object readResolve() {
        initTransient();
        return this;
    }

    public DescribableList<MansionCloudProperty, MansionCloudPropertyDescriptor> getProperties() {
        return properties;
    }

    public PlannedMansionSlaveSet getInProgressSet() {
        return inProgressSet;
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
     * Determines which labels the {@link NodeProvisioner} will request this Cloud to provision.
     *
     * @param label
     * @return true if the label is a valid template
     */
    @Override
    public boolean canProvision(Label label) {
        SlaveTemplateList list = SlaveTemplateList.get();
        SlaveTemplate st = list == null ? null : list.get(label);
        return st!=null && st.isEnabled();
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
    public Collection<PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.log(Level.FINE, "Provisioning {0} workload={1}", new Object[]{label, excessWorkload});

        final int INITIAL_SLAVES_TO_START = 3;
        final int MIN_SEC_TO_WAIT_BETWEEN_PROVISION_CYCLES = 120;
        final int THRESHOLD_SLAVE_EXCESS_LIMIT = 4;

        final SlaveTemplate st = SlaveTemplateList.get().get(label);
        if (st == null) {
            LOGGER.log(Level.FINE, "No slave template matching {0}", label);
            return Collections.emptyList();
        }
        if (!st.isEnabled()) {
            LOGGER.log(Level.FINE, "Slave template is disabled {0}", st);
            return Collections.emptyList();
        }
        if (getBackOffCounter(st).isBackOffInEffect()) {
            LOGGER.log(Level.FINE, "Back off in effect for {0}", st);
            return Collections.emptyList();
        }
        if (NEED_OVERPROVISIONING_GUARD) {
            // this check is only needed on Jenkins < 1.607
            int overEager = 0;
            for (MansionSlave n : Util.filter(Jenkins.getInstance().getNodes(), MansionSlave.class)) {
                if (n.getTemplate() == st) {
                    MansionComputer c = n.asComputer();
                    if (c != null && c.isOffline() && c.isConnecting()) {
                        overEager += n.getNumExecutors();
                    }
                }
            }
            if (overEager > excessWorkload) {
                LOGGER.log(Level.FINE,
                        "Holding off additional provisioning for {0} until the {1} pending connections complete",
                        new Object[]{st, overEager});
                return Collections.emptyList();
            } else if (overEager > 0) {
                LOGGER.log(Level.FINE,
                        "Reducing effective workload for {0} from requested {1} to {2} due to {3} pending "
                                + "connections",
                        new Object[]{st, excessWorkload, excessWorkload - overEager, overEager});
                excessWorkload -= overEager;
            }
        }

        final HardwareSpec box = getBoxOf(st, label);

        if (getQuotaProblems().isBlocked(box, st)) {
            LOGGER.log(Level.FINE, "Provisioning of {0} blocked by quota problems.", st);
            return Collections.emptyList();
        }

        String compat="";
        if (st.getLabel().equals(SlaveTemplateList.M1_COMPATIBLE)) {
            compat = " m1."+box.size;
        }

        if (box.size.equals("large")) {
            compat += " standard";
        } else if (box.size.equals("xlarge")) {
            compat += " hi-speed";
        }
        label = Jenkins.getInstance().getLabel(st.getLabel()+" "+box.size+compat);


        final Queue<PlannedMansionSlave> queue = new ArrayBlockingQueue<PlannedMansionSlave>(excessWorkload);
        List<PlannedNode> r = new ArrayList<PlannedNode>();

        /**
         * The current approach - fire up as much slaves as we can - is pretty broken.
         * Does not respect the mansion's resources and actually causes a lot of timeouts,
         * and leaves a lot of lxcs in a pretty bad state.
         *
         * A much better approach is to limit the number of provisioned slaves per M min.
         * Initially fire up N slaves. After S seconds (60-120-240?) if the build queue is still there,
         * fire up another (N-1) slaves. Increase the number of the slaves gradually and not in bursts.
         *
         * The customers will appreciate it, because they don't want to see a lot of failed provisioning requests,
         * but stable, running builds which can be achieved by the gradually started slaves.
         *
         * This change not also reduces the load on the mansions, but on the masters as well.
         *
         */
        long currentEpoch = System.currentTimeMillis()/1000;

        int currentNumberOfSlaves = 0;
        for (MansionSlave n : Util.filter(Jenkins.getInstance().getNodes(), MansionSlave.class)) {
            currentNumberOfSlaves++;

        }
        LOGGER.log(Level.INFO, "We have {0} slaves, excessWorkload = {1}", new Object[]{currentNumberOfSlaves, excessWorkload});
        // If we don't have slaves at all we need to launch immediately
        if ( currentNumberOfSlaves == 0 ) {
            //Reduce the number of the initial slaves only when it wants to fire up too many at the same time,
            if ( excessWorkload > INITIAL_SLAVES_TO_START ) {
                excessWorkload = INITIAL_SLAVES_TO_START;
            }
            lastLaunchedSlaveTimeInEpoch = currentEpoch;
        /**
         * We already have a few slaves, but still need more slaves.
         * Only start them after M min and only N -1
         */
        } else {
            if ( excessWorkload >= THRESHOLD_SLAVE_EXCESS_LIMIT ) {
                // We want to fire up more slaves after M min from the previous launch
                if (currentEpoch - lastLaunchedSlaveTimeInEpoch > MIN_SEC_TO_WAIT_BETWEEN_PROVISION_CYCLES) {
                    lastLaunchedSlaveTimeInEpoch = currentEpoch;
                    excessWorkload = INITIAL_SLAVES_TO_START - 1;
                } else {
                    /**
                     * Don't fire up more servers if we already have a few slaves
                     * and the MIN_SEC_TO_WAIT_BETWEEN_PROVISION_CYCLES hasn't passed
                     */
                    excessWorkload = 0;
                }
            } else {
                /**
                 * Don't start slaves neither even if they're below the threshold excess limit,
                 * but slaves were fired up recently. Wait a few more cycles. The build queue should go away.
                 */
                if (currentEpoch - lastLaunchedSlaveTimeInEpoch <= MIN_SEC_TO_WAIT_BETWEEN_PROVISION_CYCLES) {
                    excessWorkload = 0;
                }
            }
        }

        for (int i = 0; i < excessWorkload; i++) {
            PlannedMansionSlave plan = new PlannedMansionSlave(label, st);
            queue.add(plan);
            r.add(plan);
        }
        if (!queue.isEmpty()) {
            Computer.threadPoolForRemoting.submit(new Runnable() {
                @Override
                public void run() {
                    final int excessWorkload = queue.size();
                    final long start = System.currentTimeMillis();
                    final String oldName = Thread.currentThread().getName();
                    PlannedMansionSlave slave;
                    try {
                        Thread.currentThread().setName(String.format("Provisioning %s workload %s since %tc / %s",
                                st.getLabel(), excessWorkload, new Date(), oldName));
                        int i = 0;
                        while (null != (slave = queue.poll())) {
                            try {
                                Thread.currentThread().setName(
                                        String.format("Provisioning %s workload %s of %s since %tc / %s",
                                                st.getLabel(), i++, excessWorkload, new Date(), oldName));
                                URL broker = new URL(MansionCloud.this.broker, "/" + st.getMansionType() + "/");
                                slave.onVirtualMachineProvisioned(
                                        new BrokerRef(broker, createAccessToken(broker)).createVirtualMachine(box));
                            } catch (IOException e) {
                                handleException(st, "Failed to provision from " + this, e);
                                slave.onProvisioningFailure(e);
                                throw e;
                            } catch (OauthClientException e) {
                                handleException(st, "Authentication error from " + this, e);
                                slave.onProvisioningFailure(e);
                                throw e;
                            } catch (TooManyVirtualMachinesException e) {
                                quotaProblems.addTooManyVMProblem(e);
                                slave.onProvisioningFailure(e);
                                throw e;
                            } catch (QuotaExceededException e) {
                                quotaProblems.addProblem(e);
                                slave.onProvisioningFailure(e);
                                throw e;
                            }
                        }
                    } catch (Error e) {
                        while (null != (slave = queue.poll())) {
                            slave.onProvisioningFailure(e);
                        }
                        throw e;
                    } catch (Throwable e) {
                        while (null != (slave = queue.poll())) {
                            slave.onProvisioningFailure(e);
                        }
                    } finally {
                        Thread.currentThread().setName(oldName);
                        LOGGER.log(Level.INFO, "Provisioning {0} workload {1} took {2}ms",
                                new Object[]{st.getLabel(), excessWorkload, System.currentTimeMillis() - start});
                    }
                }
            });
        }
        return r;
    }

    /**
     * Figure out the size of the box to provision.
     *
     * If no explicit size specifier is set in the given label, this method returns "small"
     */
    protected HardwareSpec getBoxOf(SlaveTemplate st, Label label) {
        if (label==null || st.matches(label,""))
            return new HardwareSpec(st.getDefaultSize().getHardwareSize());
        if (st.matches(label,"small") || label.getName().equals("m1.small"))
            return new HardwareSpec("small");
        if (st.matches(label,"large") || st.matches(label,"standard") || label.getName().equals("m1.large"))
            return new HardwareSpec("large");
        if (st.matches(label,"xlarge") || st.matches(label,"hi-speed"))
            return new HardwareSpec("xlarge");
        throw new AssertionError("Size computation problem with label: "+label);
    }

    /**
     * Handle errors which should cause a backoff and
     * be displayed to users.
     *
     * @param msg Message for the log
     * @param e Exception to display to the user
     */
    private <T extends Exception> T handleException(SlaveTemplate st, String msg, T e) {
        LOGGER.log(WARNING, msg,e);
        this.lastException = e;
        getBackOffCounter(st).recordError();
        return e;
    }

    public Exception getLastException() {
        return lastException;
    }

    public Collection<BackOffCounter> getBackOffCounters() {
        return Collections.unmodifiableCollection(backoffCounters.values());
    }

    public QuotaProblems getQuotaProblems() {
        return quotaProblems;
    }

    public boolean isProvisioning() {
        for (PlannedMansionSlave future: getInProgressSet()) {
            if (future.isProvisioning()) {
                return true;
            }
        }
        return false;
    }

    protected BackOffCounter getBackOffCounter(SlaveTemplate st) {
        return getBackOffCounter(st.getMansionType());
    }

    private BackOffCounter getBackOffCounter(String id) {
        BackOffCounter bc = backoffCounters.get(id);
        if (bc==null) {
            backoffCounters.putIfAbsent(id, new BackOffCounter(id, 2, MAX_BACKOFF_SECONDS, TimeUnit.SECONDS));
            bc = backoffCounters.get(id);
        }
        return bc;
    }

    /**
     * Clear the back off window now.
     */
    @RequirePOST
    public HttpResponse doRetryNow(@QueryParameter String broker) {
        checkPermission(Jenkins.ADMINISTER);
        getBackOffCounter(broker).clear();
        return HttpResponses.forwardToPreviousPage();
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "CloudBees DEV@cloud Slaves";
        }

        private CloudBeesUser findUser() throws AbortException {
            // TODO: perhaps we should also let the user configure which credential to use?
            for (CloudBeesUser user : CredentialsProvider.lookupCredentials(CloudBeesUser.class)) {
                if (user.getAccounts() != null && user.getAccounts().size() > 0)
                    return user;
            }
            throw new AbortException("No cloudbees account is registered with this Jenkins instance, or the password is incorrect." +
                    " Check your Credentials.");
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

    public long lastLaunchedSlaveTimeInEpoch = System.currentTimeMillis()/1000;
}
