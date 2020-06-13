/*
 * eZEX C2O Smart Socket for Hubitat (2 sockets, E210-KR210Z1-HA) - v1.0.1
 *
 *  github: Euiho Lee (flutia)
 *  email: flutia@naver.com
 *  Date: 2020-06-13
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
    definition(name: 'eZEX Smart Socket', namespace: 'flutia', author: 'flutia') {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Refresh'
        capability 'HealthCheck'

        // switch/outlet은 둘 다 on/off command를 정의한다.
        capability 'Switch'
        capability 'Outlet'

        capability 'EnergyMeter' // attribute:energy - 전체 사용량
        capability 'PowerMeter' // attribute:power - 현재 전력 (W)
        capability 'VoltageMeasurement' // attribute:voltage  - 현재 전압

        // ezex에서는 제공하지만 Hubitat Capability에서 제공하지 않는 값들.
        attribute 'InstantaneousPower', 'number'    // 순시 전력
        attribute 'lineCurrent', 'number'    // 현재 전류
        attribute 'powerCutOff', 'string'        // 전력 차단
        attribute 'powerFactor', 'number'        // 역률
        attribute 'lock', 'string'            // 잠금

        // lock/unlock 기능은 Zigbee Command로만 제어가 가능하다. (장치상에는 lock/unlock 기능이 없음)
        command 'lock'
        command 'unlock'

        fingerprint profileId: '0104', deviceId: '0051', inClusters: '0000, 0003, 0004, 0006, 0B04, 0702', outClusters: '0019', model: 'E210-KR210Z1-HA'
    }
    preferences {
        input name: 'prefIntervalMin', type:'number', title: '최소 리포팅 주기(단위:초):', defaultValue: 30, range: '1..600', required: false
        input name: 'prefIntervalMax', type:'number', title: '최대 리포팅 주기(단위:초):', defaultValue: 60, range: '1..600', required: false
        input name: 'prefMinDeltaPower', type:'enum', title: '전력량(단위:W)이 다음 값보다 많이 바뀌면 리포팅:', options: ['1', '5', '10', '15', '25', '30', '40', '50', '70', '100'], defaultValue: '15', required: false
        input name: 'prefMinDeltaEnergy', type:'enum', title: '전력량(단위:Wh)이 다음 값보다 많이 바뀌면 리포팅:', options: ['1', '2', '3', '5', '10', '20'], defaultValue: '10', required: false
        input name: 'prefMinDeltaLineCurrent', type:'number', title: '전류량(단위:A)이 다음 값보다 많이 바뀌면 리포팅:', defaultValue: 1, range: '1..10', required: false
        input name: 'prefMinDeltaVoltage', type:'number', title: '전압(단위:V)이 다음 값보다 많이 바뀌면 리포팅:', defaultValue: 1, range: '1..10', required: false

        input name: 'prefIsDebugEnabled', type: 'bool', title: 'Enable debug logging', defaultValue: false
        input name: 'prefIsInfoEnabled', type: 'bool', title: 'Enable info logging', defaultValue: true
    }
}

def installed() {
    logD('installed')
    sendHubCommand(getMeteringUnitRefreshCommand())
}

def uninstalled() {
    logD('uninstalled')
}

def updated() {
    logD('updated')
    sendHubCommand(getMeteringUnitRefreshCommand())
}

def configure() {
    logD('configure operation')

    def cmd = getConfigureCommand()
    logD("configure command. ${cmd}")
    return cmd
}

def refresh() {
    logD('Refresh operation')

    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(getOnOffRefreshCommand())
    cmds.add(getPowerRefreshCommand())
    cmds.add(getLockRefreshCommand())
    cmds.add(getMeteringUnitRefreshCommand())
    cmds.add(getEnergyRefreshCommand())
    cmds.add(getInstantaneousDemandRefreshCommand())
    cmds.add(getLineCurrentRefreshCommand())
    cmds.add(getVoltageRefreshCommand())
    cmds.add(getPowerCutOffRefreshCommand())
    cmds.add(getPowerFactorRefreshCommand())

    sendHubCommand(cmds)
}

def ping() {
    return zigbee.onOffRefresh()
}

def parse(String description) {
    def parseMap = zigbee.parseDescriptionAsMap(description)
    def event = zigbee.getEvent(description)

    def clusterId
    def attrId
    def commandData
    if (parseMap != null) {
        clusterId = parseMap.cluster ? parseMap.cluster : parseMap.clusterId
        clusterId = (clusterId != null) ? clusterId.toUpperCase() : null
        attrId = parseMap.attrId != null ? parseMap.attrId.toUpperCase() : null
        if (parseMap.data != null) {
            commandData = String.valueOf(parseMap.data[0])
        }
    }
    logD("parseMap: ${parseMap}")

    def eventStack = []
    if (parseMap != null)  {
        def forceReturn = false

        if (clusterId == '0006') {
            if (attrId == '0000') { // on/off
                def intOnOff = zigbee.convertHexToInt(parseMap.value)
                def onOffValue = (intOnOff == 1) ? 'on' : 'off'
                logI("on/off: ${onOffValue}")
                eventStack.push(createEvent(name:'switch', value:onOffValue))
             } else if (attrId == '0010') { // refresh로 들어온 lock/unlock
                def value = zigbee.convertHexToInt(parseMap.value)
                def strValue = (value == 1) ? 'locked' : 'unlocked'
                logI("lock: ${strValue}")
                eventStack.push(createEvent( name: 'lock', value: strValue))
            } else {
                log.warn "UNKNOWN for 0006: ${description}, ${parseMap}"
            }
        } else if (clusterId == '0B04') {
            if (attrId == '0510') {  // 역률
                def powerFactor = zigbee.convertHexToInt(parseMap.value)
                logI("powerFactor: ${powerFactor}")
                eventStack.push(createEvent( name: 'powerFactor', value: powerFactor, unit: '%'))
            } else if (attrId == '050B') {  // 전력(W)
                def activePower = zigbee.convertHexToInt(parseMap.value) / 10
                logI("power: ${activePower}")
                eventStack.push(createEvent( name: 'power', value: activePower, unit: 'W'))
            }
        } else if (clusterId == '0702') {
            def renewWatt = false
            def simpleMeteringAttrProcessor = { theAttrId, value ->
                if (theAttrId == '0000') { // 총 전력
                    def energyValue = calcElectricMeasure(value)
                    logI("energy: ${energyValue}")
                    eventStack.push(createEvent(name: 'energy', value: energyValue))
                } else if (theAttrId == '0301') { // measure metering multiplier
                    def intValue = zigbee.convertHexToInt(value)
                    state.meteringMultiplier = intValue
                    logD("meteringMultiplier: ${intValue}")
                    forceReturn = true
                } else if (theAttrId == '0302') { // measure metering divisor
                    def intValue = zigbee.convertHexToInt(value)
                    state.meteringDivisor = intValue
                    logD("meteringDivisor: ${intValue}")
                    forceReturn = true
                } else if (theAttrId == '0400') { // 순시전력: Instantaneous demand (or Instantaneous power)
                    def instantPower = calcElectricMeasure(value)
                    logI("Instantaneous power: ${instantPower}")
                    eventStack.push(createEvent(name: 'InstantaneousPower', value: instantPower))
                } else if (theAttrId == '0901') { // 전류
                    def current = calcCurrentValue(value)
                    logI("current: ${current}")
                    eventStack.push(createEvent( name: 'lineCurrent', value: current))
                } else if (theAttrId == '0902') { // 전압
                    def voltage = calcCurrentValue(value)
                    logI("voltage: ${voltage}")
                    eventStack.push(createEvent( name: 'voltage', value: voltage))
                } else if (theAttrId == '0905') { // 대기전력 차단 여부
                    def powerCutOffEnabled = value == '01' ? 'enabled' : 'disabled'
                    logD("powerCutOff: ${powerCutOffEnabled}")
                    eventStack.push(createEvent( name: 'powerCutOff', value: powerCutOffEnabled))
                } else {
                    log.warn "Unhandle cluster: ${clusterId}, ${theAttrId}, ${value}, description is ${description}"
                }
            }

            def attrs = parseMap.additionalAttrs // 이젝스 확장
            if (attrs == null) {    // 이젝스 확장 값이 없으면 개별 리포팅된 케이스이다.
                simpleMeteringAttrProcessor(attrId, parseMap.value)
            } else { // 자동 리포팅: 디바이스에서 리포팅하는 정보는 attrs에 배열로 담겨져 온다.
                attrs.each { attr ->
                    simpleMeteringAttrProcessor(attr.attrId, attr.value)
                }
            }
        }

        // log.debug "eventStack: ${eventStack}, ${clusterId}, ${attrId}"
        if (!eventStack.isEmpty()) {
            return eventStack
        }
        if (forceReturn) {
            return
        }
    }

    log.warn "Unhandled Event - description: ${description}, event: ${event}"
    return event // may be null
}

def off() {
    zigbee.off()
}

def on() {
    zigbee.on()
}

def lock() {
    logD('lock operation')
    def cmds = []
    cmds << "he cmd 0x${device.deviceNetworkId} 0x1 0x0006 0x10 {}"
    cmds << 'delay 100'
    return cmds
}

def unlock() {
    logD('unlock operation')
    def cmds = []
    cmds << "he cmd 0x${device.deviceNetworkId} 0x1 0x0006 0x11 {}"
    cmds << 'delay 100'
    return cmds
}

/**
 * state 로부터 정수 값을 얻는다.
 */
private getSafeNumberValueFromState(stateKey) {
    def val = state[stateKey]
    def calVal = val ?: 0
    calVal = calVal instanceof Number ? calVal : zigbee.convertHexToInt(calVal)
    return calVal
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

/**
 * on/off 여부를 얻는 커맨드
 */
def getOnOffRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0006 0x0000", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 현재 전력을 얻는 커맨드
 */
def getPowerRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0B04 0x050B", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * Lock 여부를 얻는 커맨드
 */
def getLockRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0006 0x0010", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * meteringMultiplier/meteringDivisor 를 읽어오는 커맨드
 */
def getMeteringUnitRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0301", hubitat.device.Protocol.ZIGBEE)) //
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0302", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 누적 전력을 읽어오는 커맨드
 */
def getEnergyRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0000", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 순시 전력을 읽어오는 커맨드
 */
def getInstantaneousDemandRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0400", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 전류양을 읽어오는 커맨드
 */
def getLineCurrentRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0901", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 현재 전압을 읽어오는 커맨드
 */
def getVoltageRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0902", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 대기 전력 차단 여부를 읽어오는 커맨드
 */
def getPowerCutOffRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0702 0x0905", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * 역률을 읽어오는 커맨드
 */
def getPowerFactorRefreshCommand() {
    def cmds = new hubitat.device.HubMultiAction()
    cmds.add(new hubitat.device.HubAction("he rattr 0x${device.deviceNetworkId} 0x1 0x0B04 0x0510", hubitat.device.Protocol.ZIGBEE))
    return cmds
}

/**
 * configure reporting 커맨드
 */
def getConfigureCommand() {
    Integer intervalMin = prefIntervalMin
    Integer intervalMax = prefIntervalMax
    Integer minDeltaPower = Integer.parseInt(prefMinDeltaPower)
    Integer minDeltaEnergy = Integer.parseInt(prefMinDeltaEnergy)
    Integer minDelteLineCurrent = Integer.parseInt(prefMinDeltaLineCurrent.toString())
    Integer minDelteVoltage = Integer.parseInt(prefMinDeltaVoltage.toString())

    logI("Configuring - intervalMin:${intervalMin}, intervalMax:${intervalMax}, amount of power change:${minDeltaPower}, amount of energy change:${minDeltaEnergy}")

    def onOffConfig = zigbee.onOffConfig(intervalMin, intervalMax)
    def powerConfig = zigbee.configureReporting(0x0B04, 0x050B, 0x28, intervalMin, intervalMax, minDeltaPower * 10) // 전력
    def powerFactorConfig = zigbee.configureReporting(0x0B04, 0x0510, 0x28, intervalMin, intervalMax, 1) // 역률
    def energyConfig = zigbee.configureReporting(0x0702, 0x0000, 0x25, intervalMin, intervalMax, minDeltaEnergy) // 전체 전력
    def lineCurrentConfig = zigbee.configureReporting(0x0702, 0x0901, 0x22, intervalMin, intervalMax, minDelteLineCurrent) // 전류
    def voltageConfig = zigbee.configureReporting(0x0702, 0x0902, 0x22, intervalMin, intervalMax, minDelteVoltage) // 전압

    def cmd = onOffConfig + powerConfig + powerFactorConfig + energyConfig + lineCurrentConfig + voltageConfig
    return cmd
}

/**
 * 기기로부터 온 전력 값(순시 전력, 누적 전력)을 계산한다.
 */
def calcElectricMeasure(value) {
    def intValue = value ?: 0
    intValue = intValue instanceof Number ? intValue : zigbee.convertHexToInt(intValue)

    def multiplier = getSafeNumberValueFromState('meteringMultiplier')
    def divisor = getSafeNumberValueFromState('meteringDivisor')
    if (divisor == 0) {
        divisor = 1
    }
    def elecValue = intValue * multiplier / divisor * 1000
    return elecValue
}

/**
 * 현재 전류/전압 값을 계산한다.
 */
def calcCurrentValue(value) {
    def current = 0
    if (value) {
        current = zigbee.convertHexToInt(value)
    }
    current = current / 1000
    return current
}
