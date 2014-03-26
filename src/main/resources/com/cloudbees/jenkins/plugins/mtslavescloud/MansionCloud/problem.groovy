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
        h1("DEV@cloud Slave Provisioning Problems")

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

        showProblem("Broker Problem",0,my.lastException)
        my.inProgressSet.each { PlannedMansionSlave s ->
            showProblem("Slave ${s.vm.url} Problem", s.problemTimestamp, s.problem)
        }
    }
}
