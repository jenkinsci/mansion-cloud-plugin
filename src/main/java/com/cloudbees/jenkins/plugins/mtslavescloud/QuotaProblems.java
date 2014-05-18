package com.cloudbees.jenkins.plugins.mtslavescloud;

import com.cloudbees.jenkins.plugins.mtslavescloud.templates.SlaveTemplate;
import com.cloudbees.mtslaves.client.HardwareSpec;
import com.cloudbees.mtslaves.client.QuotaExceededException;
import com.cloudbees.mtslaves.client.TooManyVirtualMachinesException;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.PeriodicWork;
import hudson.slaves.ComputerListener;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.Iterators.concat;

/**
 * Keeps track of problems related to quota over-usage.
 * Allows the user to view and manage these problems.
 *
 * This can include:
 * <ul>
 *     <ol>Not having a subscription</ol>
 *     <ol>Suspended for non-payment</ol>
 *     <ol>Using sizes/hardware which aren't allowed</ol>
 *     <old>Using too many virtual machines at once</old>
 * </ul>
 *
 * Most quota problems are related to a specific vmType.
 * For example, if you are using the maximum lxc virtual
 * machines, you should still be allowed to provision
 * additional osx virtual machines.
 *
 * @author Ryan Campbell
 */
public class QuotaProblems implements Iterable<QuotaProblems.QuotaProblem> {


    public static class QuotaProblem {
        private final QuotaExceededException exception;

        public QuotaProblem(QuotaExceededException e) {
            this.exception = e;
        }

        /**
         * Determines if this QuotaException indicates whether new VM provisioning should be prevented.
         *
         * Some QuotaExceptions are global (for instance, if the account is not subscribed),
         * while others affect all sizes of a VMType, or others which only affect certain sizes.
         * @param spec
         * @param vmType
         * @return
         */
        public boolean blocksProvisioningOf(HardwareSpec spec, String vmType) {
            return blocksAll()
                    || (blocksVmType() && vmType.equals(exception.getVMType()))
                    || (vmType.equals(exception.getVMType()) && spec.size.equals(exception.getHardwareSize()));
        }

        private boolean blocksAll() {
            return exception.getHardwareSize() == null && exception.getVMType() == null;
        }

        private boolean blocksVmType() {
            return exception.getVMType() != null && exception.getHardwareSize() == null;
        }

        public String getMessage() {
            if (blocksAll()) {
                return exception.getMessage();
            } else if (blocksVmType()) {
                return "Unable to provision " + exception.getVMType() + " : " + exception.getMessage()
                        + (exception instanceof TooManyVirtualMachinesException ?
                            " (Will retry automatically)" : "");

            } else {
                return "Unable to provision " + exception.getHardwareSize() +
                        " from: " + exception.getVMType() + " : " + exception.getMessage();
            }
        }
    }


    /**
     * General quota problems which we don't expect to change
     * unless a user upgrades, for example.
     */
    private List<QuotaProblem> problems = new ArrayList<QuotaProblem>();

    /**
     * These problems indicate the account is using
     * too many virtual machines. Since this state changes
     * over time, these problems are cleared automatically
     * so that the user doesn't have to take a manual action.
     */
    private List<QuotaProblem> tooManyVmProblems = new ArrayList<QuotaProblem>();


    public void addProblem(QuotaExceededException exception) {
        problems.add(new QuotaProblem(exception));
    }

    public void addTooManyVMProblem(TooManyVirtualMachinesException e) {
        tooManyVmProblems.add(new QuotaProblem(e));
    }


    public boolean isBlocked(HardwareSpec spec, SlaveTemplate template) {
        for (QuotaProblem p : Iterables.concat(problems, tooManyVmProblems)) {
            if (p.blocksProvisioningOf(spec,template.getMansionType())) {
                return true;
            }
        }
        return false;
    }


    public Iterator<QuotaProblem> iterator() {
        return concat(problems.iterator(), tooManyVmProblems.iterator());
    }

    public Integer getSize() {
        return problems.size() + tooManyVmProblems.size();
    }

    @RequirePOST
    public HttpResponse doClear() {
        Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        problems.clear();
        tooManyVmProblems.clear();
        return new HttpRedirect(Jenkins.getInstance().getRootUrl());

    }

    /**
     * Clear tooManyVmProblems list whenever we get rid of a computer.
     * Users shouldn't have to press retry if we know there
     * might be more capacity available.
     */
    @Extension
    public static class TooManyVMCleaner extends ComputerListener {
        @Override
        public void onOffline(Computer c) {
            if (c instanceof MansionComputer) {
                for (MansionCloud cloud : Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
                    cloud.getQuotaProblems().tooManyVmProblems.clear();
                }
            }
        }
    }


    /**
     * Periodically clear any quota issues related to having too many VMs.
     * There are cases where the {@code TooManyVMCleaner} won't catch
     * a change in system usage.
     */
    @Extension
    public static class PeriodicTooManyVMCleaner extends PeriodicWork {

        @Override
        protected void doRun() throws Exception {
            for (MansionCloud cloud : Jenkins.getInstance().clouds.getAll(MansionCloud.class)) {
                cloud.getQuotaProblems().tooManyVmProblems.clear();
            }
        }

        @Override
        public long getRecurrencePeriod() {
            return TimeUnit.MINUTES.toMillis(5);
        }
    }
}
