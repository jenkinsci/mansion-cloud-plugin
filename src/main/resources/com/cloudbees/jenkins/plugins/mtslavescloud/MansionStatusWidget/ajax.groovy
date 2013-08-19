package com.cloudbees.jenkins.plugins.mtslavescloud.MansionStatusWidget

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud
import com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave
import hudson.Util
import jenkins.model.Jenkins;

def l = namespace(lib.LayoutTagLib);

def row(style,body) {
    tr(class:"no-wrap") {
        td(class:"pane",colspan:2,style:style) {
            body()
        }
    }
}

l.ajax {
    l.pane(width:2, title:_("Cloud Slave Provisioning"), id:"mansion-status") {
        def mansions = Jenkins.getInstance().clouds.getAll(MansionCloud.class)
        mansions.each { m ->

            // per-cloud optional header
            if (mansions.size()>1) {
                // if there are multiple clouds, put a title to distinguish them
                row("text-align:center; font-weight:bold") {
                    text(m.account)
                }
            }

            boolean shownSomething = false;

            // error from the cloud itself
            def bc = m.backOffCounter
            if (bc.isBackOffInEffect()) {
                // if we are having a problem, report that
                row(null) {
                    div(class:"warning") {
                        div {
                            a(href:"${rootURL}/cloud/${m.name}/problem", _("Currently experiencing problem"))
                        }
                        div("Will try again in ${Util.getTimeSpanString(bc.nextAttempt-System.currentTimeMillis())}")
                        shownSomething = true
                    }
                }
            }

            // in-progress allocations
            m.inProgressSet.each { PlannedMansionSlave s ->
                include(s,"status")
                shownSomething = true
            }

            if (!shownSomething) {
                row("color:#bbb") {
                    text("(idle)")
                }
            }
        }
    }
}
