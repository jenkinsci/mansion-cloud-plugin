package com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave

def f = namespace(lib.FormTagLib);
def l = namespace(lib.LayoutTagLib);

tr(class:"no-wrap") {
    td(class:"pane") {
        a(href: my.shouldHyperlinkSlave()?my.vm.url:null, my.displayName)
    }
    td(class:"pane", my.status)
}
