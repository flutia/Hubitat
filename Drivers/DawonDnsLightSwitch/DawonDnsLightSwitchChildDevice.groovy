/**
 * DAWON DNS Light Switch Child Device for Hubitat - v1.1.0
 *
 *  github: Euiho Lee (flutia)
 *  email: flutia@naver.com
 *  Date: 2020-08-07
 *  Copyright flutia and stsmarthome (cafe.naver.com/stsmarthome/)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy
 *  of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
metadata {
    definition(name: "DAWON DNS Light Switch Child Device (STS)", namespace: "flutia", author: "flutia", component: true) {
        capability "Switch"
        capability "Refresh"
        capability "Actuator"
       
    }
    preferences {
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
    }
}

void updated() {
    logI("Updated...")
    logI("debug logging is: ${logEnable == true}")
}

void installed() {
    logI("Installed...")
    device.updateSetting("logEnable",[type:"bool",value:true])
    refresh()
}

void parse(String description) {
    logW("parse(String description) not implemented")
    }

void parse(List description) {
    logD("parse(List description) - ${description}")
    description.each {
        if (it.name in ["switch"]) {
            logI(it.descriptionText)
            sendEvent(it)
        }
    }
}

def on() {
    logD("child-on ${this.device}")
    parent?.onOrOffChild(this.device, true)
}

def off() {
    logD("child-off ${this.device}")
    parent?.onOrOffChild(this.device, false)
}

def refresh() {
    logD("child-refresh ${this.device}")
    parent?.childRefresh(this.device)
}

void logD(msg) {
    if (logEnable) {
        log.debug(msg)
    }
}

void logI(msg) {
    if (logEnable) {
        log.info(msg)
    }
}

void logW(msg) {
    if (logEnable) {
        log.warn(msg)
    }
}