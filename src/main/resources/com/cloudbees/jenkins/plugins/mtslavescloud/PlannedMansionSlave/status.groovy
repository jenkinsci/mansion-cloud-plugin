package com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave

def f = namespace(lib.FormTagLib);
def l = namespace(lib.LayoutTagLib);

tr(class:"no-wrap") {
    td(class:"pane",colspan:2) {
        text(my.toString()) // TODO: next phase will expand on this
    }
}
