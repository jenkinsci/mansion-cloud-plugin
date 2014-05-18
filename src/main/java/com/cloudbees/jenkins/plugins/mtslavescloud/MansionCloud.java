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
import com.cloudbees.mtslaves.client.VirtualMachineRef;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesAccount;
import com.cloudbees.plugins.credentials.cloudbees.CloudBeesUser;
import hudson.AbortException;
import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.AbstractCloudImpl;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.DescribableList;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
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
        this(broker,null,null);
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
     * Determines which labels the {@NodeProvisioner will request this Cloud to provision.
     *
     * @param label
     * @return true if the label is a valid template
     */
    @Override
    public boolean canProvision(Label label) {
        SlaveTemplate st = SlaveTemplateList.get().get(label);
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
    public Collection<PlannedNode> provision(final Label label, int excessWorkload) {
        LOGGER.fine("Provisioning "+label+" workload="+excessWorkload);

        SlaveTemplate st = SlaveTemplateList.get().get(label);
        if (st==null) {
            LOGGER.fine("No slave template matching "+label);
            return Collections.emptyList();
        }
        if (!st.isEnabled()) {
            LOGGER.fine("Slave template is disabled "+st);
            return Collections.emptyList();
        }
        if (getBackOffCounter(st).isBackOffInEffect()) {
            LOGGER.fine("Back off in effect for "+st);
            return Collections.emptyList();
        }
        HardwareSpec box = getBoxOf(st,label);

        if (getQuotaProblems().isBlocked(box, st)) {
            LOGGER.fine("Provisioning blocked by quota problems.");
            return Collections.emptyList();
        }

        List<PlannedNode> r = new ArrayList<PlannedNode>();
        try {
            provisioning = true;
            for (int i=0; i<excessWorkload; i++) {

                URL broker = new URL(this.broker,"/"+st.getMansionType()+"/");
                final VirtualMachineRef vm = new BrokerRef(broker,createAccessToken(broker)).createVirtualMachine(box);
                LOGGER.fine("Allocated "+vm.url);

                String compat="";
                if (st.getLabel().equals(SlaveTemplateList.M1_COMPATIBLE)) {
                    compat = " m1."+box.size;
                }

                if (box.size.equals("large")) {
                    compat += " standard";
                } else if (box.size.equals("xlarge")) {
                    compat += " hi-speed";
                }

                r.add(new PlannedMansionSlave(Jenkins.getInstance().getLabel(st.getLabel()+" "+box.size+compat), st, vm));
            }
        } catch (IOException e) {
            handleException(st, "Failed to provision from " + this, e);
        } catch (OauthClientException e) {
            handleException(st, "Authentication error from " + this, e);
        } catch (TooManyVirtualMachinesException e) {
            quotaProblems.addTooManyVMProblem(e);
        } catch (QuotaExceededException e) {
            quotaProblems.addProblem(e);
        } finally {
            provisioning = false;
        }
        return r;
    }

    /**
     * Figure out the size of the box to provision.
     *
     * If no explicit size specifier is set in the given label, this method returns "small"
     */
    private HardwareSpec getBoxOf(SlaveTemplate st, Label label) {
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
        return provisioning;
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
