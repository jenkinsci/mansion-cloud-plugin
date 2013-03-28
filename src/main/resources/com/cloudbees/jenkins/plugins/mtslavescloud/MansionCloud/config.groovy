package com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud;

def f = namespace(lib.FormTagLib)

f.entry(field:"broker",title:_("Broker URL")) {
    f.textbox()
}