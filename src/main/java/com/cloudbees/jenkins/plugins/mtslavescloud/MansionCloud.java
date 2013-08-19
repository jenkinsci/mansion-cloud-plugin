package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.EndPoints;
import com.cloudbees.api.BeesClient;
import com.cloudbees.api.TokenGenerator;
import com.cloudbees.api.cr.Capability;
import com.cloudbees.api.cr.Credential;
import com.cloudbees.api.oauth.OauthClientException;
import com.cloudbees.api.oauth.TokenRequest;
import com.cloudbees.jenkins.plugins.mtslavescloud.util.BackOffCounter;
import com.cloudbees.mtslaves.client.BrokerRef;
import com.cloudbees.mtslaves.client.HardwareSpec;
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
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private transient /*almost final*/ BackOffCounter backoffCounter;

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
            return tokenGenerator.createToken(tr).asCredential();
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
        backoffCounter = new BackOffCounter(2, MAX_BACKOFF_SECONDS, TimeUnit.SECONDS);
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
        if (backoffCounter.isBackOffInEffect()) {
            return Collections.emptyList();
        }

        LOGGER.fine("Provisioning "+label+" workload="+excessWorkload);


        List<PlannedNode> r = new ArrayList<PlannedNode>();
        try {
            for (int i=0; i<excessWorkload; i++) {
                final SlaveTemplate st = resolveToTemplate(label);

                HardwareSpec box;
                if (label != null && label.toString().contains(".")) {
                    box = new HardwareSpec(label.toString().substring(label.toString().lastIndexOf(".") + 1));
                } else {
                    box = new HardwareSpec("small");
                }

                URL broker = new URL(this.broker,"/"+st.mansion+"/");
                final VirtualMachineRef vm = new BrokerRef(broker,createAccessToken(broker)).createVirtualMachine(box);
                LOGGER.fine("Allocated "+vm.url);

                r.add(new PlannedMansionSlave(label, st, vm));
            }
        } catch (IOException e) {
            handleException("Failed to provision from " + this, e);
        } catch (OauthClientException e) {
            handleException("Authentication error from " + this, e);
        }
        return r;
    }

    /**
     * Handle errors which should cause a backoff and
     * be displayed to users.
     *
     * @param msg Message for the log
     * @param e Exception to display to the user
     */
    private <T extends Exception> T handleException(String msg, T e) {
        LOGGER.log(WARNING, msg,e);
        this.lastException = e;
        backoffCounter.recordError();
        return e;
    }

    public Exception getLastException() {
        return lastException;
    }

    public BackOffCounter getBackOffCounter() {
        return backoffCounter;
    }

    /**
     * Clear the back off window now.
     */
    @RequirePOST
    public HttpResponse doRetryNow() {
        checkPermission(Jenkins.ADMINISTER);
        getBackOffCounter().clear();
        return HttpResponses.forwardToPreviousPage();
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
