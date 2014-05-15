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

package com.cloudbees.jenkins.plugins.mtslavescloud.MansionStatusWidget

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud
import com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave
import hudson.Util
import jenkins.model.Jenkins;

def l = namespace(lib.LayoutTagLib);
def f = namespace(lib.FormTagLib)

def row(style,body) {
    tr {
        td(class:"pane",colspan:2,style:style) {
            body()
        }
    }
}

l.ajax {
    l.pane(width:2, title:_("DEV@cloud Slave Provisioning"), id:"mansion-status") {
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
            m.backOffCounters.each { bc ->
                if (bc.isBackOffInEffect()) {
                    // if we are having a problem, report that
                    row(null) {
                        div(class:"warning") {
                            div {
                                a(href:"${rootURL}/cloud/${m.name}/problem", bc.id)
                            }
                            div("Currently experiencing problems. Will try again in ${Util.getTimeSpanString(bc.nextAttempt-System.currentTimeMillis())}")
                            shownSomething = true
                        }
                    }
                }
            }

            m.quotaProblems.each { p ->
                row(null) {
                    div(class:"warning") {
                        div(style:'max-width:10em; word-wrap:normal',p.message)
                        shownSomething = true
                    }
                }
            }
            if (m.quotaProblems.size > 0) {
                row(null) {
                    form(method:"post",action:"${rootURL}/cloud/${m.name}/quotaProblems/clear", style:"float:right; display:block") {
                        f.submit(value:_("Retry"))
                    }
                }
            }

            // in-progress allocations
            m.inProgressSet.each { PlannedMansionSlave s ->
                include(s,"status")
                shownSomething = true
            }

            if (!shownSomething) {
                row("color:#888") {
                    div(align:"center", "idle")
                }
            }
        }
    }
}
