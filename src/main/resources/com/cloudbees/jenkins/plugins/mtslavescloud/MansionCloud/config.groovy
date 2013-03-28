package com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloud

import com.cloudbees.jenkins.plugins.mtslavescloud.MansionCloudPropertyDescriptor;

def f = namespace(lib.FormTagLib)

f.entry(field:"broker",title:_("Broker URL")) {
    f.textbox()
}

f.descriptorList(field:"properties",  title:_("Properties"), descriptors:MansionCloudPropertyDescriptor.all())
