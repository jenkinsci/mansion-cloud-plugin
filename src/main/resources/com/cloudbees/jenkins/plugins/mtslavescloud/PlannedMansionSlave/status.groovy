package com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave

def f = namespace(lib.FormTagLib);
def l = namespace(lib.LayoutTagLib);

tr(class:"no-wrap") {
    td(class:"pane",style:"width:1px; text-wrap:no-wrap") {// smallest possible width
        a(href: my.shouldHyperlinkSlave()?my.vm.url:null, my.displayName)
    }
    td(class:"pane") {
        a(href: my.problem!=null?"${rootURL}/${my.url}/problem":null, my.status)
    }
}
