/**
 * DAWON DNS Smart Plug 16A for Hubitat - v1.0.4
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
    definition (name: 'DAWON DNS Smart Plug 16A', namespace: 'flutia', author:'flutia') {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Refresh'
        capability 'EnergyMeter'    // 누적 사용량 Wh
        capability 'PowerMeter'     // 현재 전력량 와트(W)
        capability 'VoltageMeasurement' // TODO: 0x0B04 클러스터 지원 유무 확인.
        capability 'Sensor'
        capability 'Switch'
        capability 'Health Check'

        // 0B04 cluster를 지원하지만 실제로는 지원되는 attribute가 없는 것으로 보인다.
        fingerprint profileId: '0104', inClusters: '0000,0002,0003,0004,0006,0019,0702,0B04,0008,0009', outClusters: '0000,0002,0003,0004,0006,0019,0702,0B04,0008,0009', model: 'PM-B530-ZB', manufacturer:'DAWON_DNS', deviceJoinName: 'DAWON DNS Smart Plug 16A'
        fingerprint profileId: '0104', inClusters: '0000,0002,0003,0006,0702,0B04', outClusters: '0003,0019', model: 'PM-B540-ZB', manufacturer:'DAWON_DNS', deviceJoinName: 'DAWON DNS Smart Plug 16A'
    }
    preferences {
        section() {
            input name: 'prefIsReportSet', type:'bool', title: 'Enable reporting', defaultValue: false
            input name: 'prefIntervalMin', type:'number', title: 'Minimum interval (seconds) between reports:', defaultValue: 30, range: '1..600', required: false
            input name: 'prefIntervalMax', type:'number', title: 'Maximum interval (seconds) between reports:', defaultValue: 300, range: '1..600', required: false
            input name: 'prefMinDeltaPower', type:'enum', title: 'Amount of power change (W) required to trigger a report:', options: ['1', '5', '10', '15', '25', '50'], defaultValue: '5', required: false
            input name: 'prefMinDeltaEnergy', type:'enum', title: 'Amount of energy change (Wh) required to trigger a report:', options: ['1', '2', '3', '5', '10', '20'], defaultValue: '1', required: false
        }
        section() {
            input name: 'prefIsDebugEnabled', type: 'bool', title: 'Enable debug logging', defaultValue: false
            input name: 'prefIsInfoEnabled', type: 'bool', title: 'Enable info logging', defaultValue: true
        }
    }
}

/*
parse 처리시 유의사항
- app 에서 on/off를 했을 땐 catchall 이 먼저 올라오고 나중에 raw 값이 올라온다.
*/

def parse(String description) {
    logD("description: ${description}")

    def parseResult = zigbee.parse(description) // SmartShield Object returned
    logD("parseResult: ${parserResult?.isPhysical}")

    def parseMap = zigbee.parseDescriptionAsMap(description)
    logD("parseMap: ${paseMap}")

    def event = zigbee.getEvent(description)
    logD("event: ${event}")

    // on/off 일 경우 event가 정상적으로 파싱되지만, metering인 경우 cluster를 확인해야 한다.
    if (parseMap?.cluster == '0702') {
        if (parseMap.attrId == '0000') {
            // report된 energyValue는 Wh 단위이다. kWh로 변환해준다.
            def energyValue = zigbee.convertHexToInt(parseMap.value) / 1000
            event = createEvent(name: 'energy', value: energyValue, unit:'kWh')
            logI("energy: ${energyValue}")
        } else if (parseMap.attrId == '0400') {
            def powerValue = zigbee.convertHexToInt(parseMap.value)
            event = createEvent(name: 'power', value: powerValue, unit:'W', descriptionText: "${device.displayName} power: ${powerValue} watts")
            logI("power: ${powerValue}")
        }
    } else if (event?.name == 'switch') {
        logI("switch: ${event.value}")
        if (event.value == 'off') {
            sendEvent(name: 'power', value:0, unit:'W', descriptionText: "${device.displayName} power: 0 watts (switch off)")
        }
    } else {
        logW("Unhandled: ${description}, parseMap: ${parseMap}")
    }

    return event
}

def installed() {
    logD('installed')
}

def uninstalled() {
    logD('uninstalled')
    // 리포팅을 해제한다.
    return getConfigureCommand(false)
}

def updated() {
    logD('updated')
    configure()
}

def configure() {
    logD('configure')
    return getConfigureCommand(prefIsReportSet)
}

def getConfigureCommand(isReportEnabled) {
    Integer intervalMin = 0
    Integer intervalMax = 0
    Integer minDeltaPower = 0
    Integer minDeltaEnergy = 0
    if (isReportEnabled) {
        intervalMin = prefIntervalMin
        intervalMax = prefIntervalMax
        minDeltaPower = Integer.parseInt(prefMinDeltaPower)
        minDeltaEnergy = Integer.parseInt(prefMinDeltaEnergy)
        logI("Configuring - intervalMin:${intervalMin}, intervalMax:${intervalMax}, amount of power change:${minDeltaPower}, amount of energy change:${minDeltaEnergy}")
    } else {
        logI('Configure reporting is diabled')
    }

    def onOffConfig = zigbee.onOffConfig(intervalMin, intervalMax)
    // 10A 플러그는 power configure reporting을 지원하지 않는다.
    def powerConfig = zigbee.configureReporting(0x0702, 0x0400, 0x2A, intervalMin, intervalMax, minDeltaPower)
    def energyConfig = zigbee.configureReporting(0x0702, 0x0000, 0x25, intervalMin, intervalMax, minDeltaEnergy)

    def cmd = onOffConfig + powerConfig + energyConfig
    return cmd
}

def ping() {
    logD('ping')
    return refresh()
}

def off() {
    logD('off called')
    zigbee.off()
}

def on() {
    logD('on called')
    zigbee.on()
}

def refresh() {
    logD('refresh')

    def onOffRefresh = zigbee.onOffRefresh() // zigbee.readAttribute(0x0006, 0x0000)
    def currentPower = zigbee.readAttribute(0x0702, 0x0400)
    def historicalPower = zigbee.readAttribute(0x0702, 0x0000)
    // ClusterID: 0x0B04는 다원이 지원하지 않는다.

    def refreshCommand = onOffRefresh + currentPower + historicalPower
    return refreshCommand
}

void logD(String msg) {
    if (prefIsDebugEnabled) {
        log.debug(msg)
    }
}

void logI(String msg) {
    if (prefIsInfoEnabled) {
        log.info(msg)
    }
}

void logW(String msg) {
    log.warn(msg)
}

void logE(String msg) {
    log.error(msg)
}
