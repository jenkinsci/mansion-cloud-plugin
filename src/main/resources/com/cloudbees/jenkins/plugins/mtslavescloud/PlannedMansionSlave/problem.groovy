package com.cloudbees.jenkins.plugins.mtslavescloud.PlannedMansionSlave

import hudson.Functions

def l = namespace(lib.LayoutTagLib)

l.layout {
    l.main_panel {
        h1("Slave Provisioning Problem")

        pre(Functions.printThrowable(my.problem))
    }
}
