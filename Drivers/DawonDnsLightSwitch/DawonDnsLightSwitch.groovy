/**
 * DAWON DNS Light Switch for Hubitat - v1.1.1
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
    definition (name: 'DAWON DNS Light Switch (STS)', namespace: 'flutia', author:'flutia') {
        capability 'Actuator'
        capability 'Switch'
        capability 'Refresh'

        fingerprint profileId: '0104', inClusters: '0000,0004,0003,0006,0019,0002,0009', outClusters: '0000,0004,0003,0006,0019,0002,0009', model: 'PM-S240-ZB', manufacturer:'DAWON_DNS', deviceJoinName: 'DAWON DNS Light Switch 2'
    }
    preferences {
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: true
        input name: 'txtEnable', type: 'bool', title: 'Enable descriptionText logging', defaultValue: true
        input name: 'buttonCount', type: 'number', title: 'Number of light switch buttons', defaultValue: 0, required: true
    }
}

void installed() {
    createChildDevices()
}

void updated() {
    logI('updated...')
    logW("debug logging is: ${logEnable == true}")
    logW("description logging is: ${txtEnable == true}")

    if (!childDevices || childDevices.size() != buttonCount) {
        createChildDevices()
    }
}

void createChildDevices() {
    if (buttonCount < 1) {
        logW('Button count is not set.')
        return
    }
    for (ep in 1..buttonCount) {
        def childDevice
        def children = getChildDevices()
        if (children != null) {
            childDevice = childDevices.find { it ->
                def currentEP = getEPFromChildDNID(it.deviceNetworkId)
                return ep == currentEP
            }
        }
        if (childDevice) {
            continue
        }

        def childDeviceId = "${device.deviceNetworkId}-ep${ep}"
        logI("child created - ${ep}, ${device.deviceNetworkId}-${ep}, ${device.displayName}")

        addChildDevice('flutia', 'DAWON DNS Light Switch Child Device (STS)', childDeviceId, [name: "${device.displayName} - ${ep}번 버튼", isComponent: false])
    }
}

def parse(String description) {
    logD("description: ${description}")

    def parseResult = zigbee.parse(description)
    logD("parseResult: ${parserResult}")

    def parseMap = zigbee.parseDescriptionAsMap(description)
    logD("parseMap: ${parseMap}")

    def event = zigbee.getEvent(description)
    logD("event: ${event}")

    if (event.name == 'switch') {
        def epValue
        if (parseMap['endpoint']) {
            epValue = Integer.parseInt(parseMap['endpoint'])
        } else if (parseMap['sourceEndpoint']) {
            epValue = Integer.parseInt(parseMap['sourceEndpoint'])
        }
        fetchChild(epValue).sendEvent(name:'switch', value:event.value, descriptionText:"turned ${event.value}")

        // ----------------------------
        // parent 스위치 상태 반영

        // on 일 때는 parent 도 무조건 on
        if (event.value == 'on') {
            sendEvent(name: 'switch', value: 'on', displayed: false)
            return
        }

        // off 된 child 확인
        boolean isOnAny = false
        def childOn = childDevices.find { cd ->
            def ep = getEPFromChildDNID(cd.deviceNetworkId)
            if (ep == epValue) {
                return false
            }
            return cd.currentValue('switch') == 'on'
        }
        if (childOn) {
            isOnAny = true
        }
        sendEvent(name: 'switch', value: isOnAny ? 'on' : 'off', displayed: false)
        return
    }

    logD("Unhandled Event - commandType: ${commandType}, description: ${description}, parseMap: ${parseMap}, event: ${event}")
    return null
}

def fetchChild(strEP) {
    int ep = strEP as int
    def childDeviceId = "${device.deviceNetworkId}-ep${ep}"
    def childDevice = getChildDevice(childDeviceId)
    return childDevice
}

def on() {
    log.debug "Executing 'on all' for 0x${device.deviceNetworkId}"
    def cmds = new hubitat.device.HubMultiAction()
    int onOrOff = 0x01
    for (ep in 1..buttonCount) {
        cmds.add(new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} ${ep} 0x0006 ${onOrOff} {}", hubitat.device.Protocol.ZIGBEE))
    }
    sendHubCommand(cmds)
}

def off() {
    log.debug "Executing 'off all' for 0x${device.deviceNetworkId}"
    def cmds = new hubitat.device.HubMultiAction()
    int onOrOff = 0x00
    for (ep in 1..buttonCount) {
        cmds.add(new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} ${ep} 0x0006 ${onOrOff} {}", hubitat.device.Protocol.ZIGBEE))
    }
    sendHubCommand(cmds)
}

def refresh() {
    logD('refresh')
    def cmds = []
    for (i in 1..buttonCount) {
        cmds += zigbee.readAttribute(0x0006, 0x0000, [destEndpoint: i])
    }
    return cmds
}

// ----------------------------------------
// child device methods
// ----------------------------------------

void childRefresh(cd) {
    logD("received refresh request from ${cd.displayName}")

    def ep = getEPFromChildDNID(cd.deviceNetworkId)
    logD("Executing 'refresh' for 0x${device.deviceNetworkId} endpoint ${ep},  child: ${dnId}")

    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} ${ep} 0x0006 0x0000", hubitat.device.Protocol.ZIGBEE))
    sendHubCommand(cmds)
}

Integer getEPFromChildDNID(String dnId) {
    String epToken = dnId.split('-')[-1]
    String ep = epToken.replace('ep', '')
    return ep as Integer
}

def onOrOffChild(cd, isOn) {
    logD("received on request from ${cd.displayName}")

    Integer ep = getEPFromChildDNID(cd.deviceNetworkId)
    Integer onOrOff = isOn ? 0x01 : 0x00

    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he cmd 0x${device.deviceNetworkId} ${ep} 0x0006 ${onOrOff} {}", hubitat.device.Protocol.ZIGBEE))
    sendHubCommand(cmds)
}

void logD(String msg) {
    if (logEnable) {
        log.debug(msg)
    }
}

void logI(String msg) {
    if (logEnable) {
        log.info(msg)
    }
}

void logW(String msg) {
    if (logEnable) {
        log.warn(msg)
    }
}
