package com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave

import hudson.Functions

def l = namespace(lib.LayoutTagLib)
def f = namespace(lib.FormTagLib)

l.layout {
    l.main_panel {
        h1 {
            form(method:"post",action:"dismiss", style:"margin:1em; float:right; display:block") {
                f.submit(value:_("Dismiss this error"))
            }
            text("Cloud Executor Provisioning Problem: ${new Date(my.problemTimestamp)}")
        }


        pre(Functions.printThrowable(my.problem))
    }
}
