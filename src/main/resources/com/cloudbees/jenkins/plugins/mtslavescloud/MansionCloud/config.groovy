package com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud
import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloudPropertyDescriptor
import com.cloudbees.jenkins.plugins.mtslavescloud.MansionPlugin;

def f = namespace(lib.FormTagLib)

f.entry(field:"broker",title:_("Broker URL")) {
    f.textbox(default:MansionPlugin.MANSION_BROKER_URL)
}

if (!MansionCloud.isInDevAtCloud()) {// in DEV@cloud, the implicit user credential only has one account
    f.entry(field:"account",title:_("Account")) {
        f.select()
    }
}

f.descriptorList(field:"properties",  title:_("Properties"), descriptors:MansionCloudPropertyDescriptor.all())
