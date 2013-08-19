package com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud

import com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave
import hudson.Functions

import static hudson.Util.getPastTimeString
import static hudson.Util.getTimeSpanString

def f = namespace(lib.FormTagLib)
def l = namespace(lib.LayoutTagLib)

def showProblem(String title, long timestamp, Throwable t) {
    if (t==null)    return; // nothing to report

    h2(title)
    if (timestamp>0)
        div(style:"text-align:right", getPastTimeString(System.currentTimeMillis()-timestamp))
    pre(Functions.printThrowable(t))
}

l.layout {
    l.main_panel {
        h1("Slave Provisioning Problems")

        my.backOffCounters.each { bc ->
            if (bc.isBackOffInEffect()) {
                // if we are having a problem, report that
                div(class:"warning",
                    _("Requesting of ${bc.id} cloud slaves are temporarily suspended due to an earlier problem. ")+
                    _("It will be tried again in ${getTimeSpanString(bc.nextAttempt-System.currentTimeMillis())}"))

                form(method:"post",action:"retryNow?broker=${bc.id}", style:"margin:1em") {
                    f.submit(value:_("Retry Now"))
                }
            }
        }

        div(style:"padding-top:2em","The rest of the page shows recent problems")

        showProblem("Cloud Problem",0,my.lastException)
        my.inProgressSet.each { PlannedMansionSlave s ->
            showProblem("Slave ${s.vm.url} Problem", s.problemTimestamp, s.problem)
        }
    }
}
