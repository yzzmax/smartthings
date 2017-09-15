/**
* Zigbee Hue Lux Bulb
*
* Copyright 2015 Jason Steele
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
* in compliance with the License. You may obtain a copy of the License at:
*
* http://www.apache.org/licenses/LICENSE-2.035
*
* Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
* on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
* for the specific language governing permissions and limitations under the License.
*
* Thanks all the contributors to the GE Link bulb device handler at 
* http://community.smartthings.com/t/updated-ge-link-bulbs-finally-getting-on-status-after-manually-turning-on48
* and special thanks to to @Sticks18 for all his help with this
*/

metadata {
definition (name: "Zigbee Hue Lux Bulb", namespace: "JasonBSteele", author: "Jason Steele") {
capability "Switch Level"
capability "Actuator"
capability "Switch"
capability "Configuration"
capability "Polling"
capability "Refresh"
capability "Sensor"

	fingerprint profileId: "C05E", inClusters: "0000,0003,0004,0005,0006,0008,1000", outClusters: "0019"
}

// simulator metadata
simulator {
	// status messages
	status "on": "on/off: 1"
	status "off": "on/off: 0"

	// reply messages
	reply "zcl on-off on": "on/off: 1"
	reply "zcl on-off off": "on/off: 0"
}

// UI tile definitions
tiles {
	standardTile("switch", "device.switch", width: 2, height: 2, canChangeIcon: true) {
    	state "off", label: '${name}', action: "switch.on", icon: "st.switches.light.off", backgroundColor: "#ffffff"
		state "on", label: '${name}', action: "switch.off", icon: "st.switches.light.on", backgroundColor: "#79b821"
	}
	standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat") {
		state "default", label:"", action:"refresh.refresh", icon:"st.secondary.refresh"
	}
	controlTile("levelSliderControl", "device.level", "slider", height: 1, width: 3, inactiveLabel: false) {
		state "level", action:"switch level.setLevel"
	}
	valueTile("level", "device.level", inactiveLabel: false, decoration: "flat") {
		state "level", label: 'Level ${currentValue}%'
	}

	main(["switch"])
	details(["switch", "levelSliderControl", "refresh"])
}
}

// Parse incoming device messages to generate events
def parse(String description) {
log.trace "parse(${description})"

def result = null

//Refresh causes catchall: 0104 0006 0B 01 0100 00 5C14 00 00 0000 01 01 0000001001 to be sent if On 
//(and 1000 if Off) and "read attr" for level 
//0000 and 0100 are returned by the On/Off being tapped in the app so we are already raising an event for this
//(however these only get returned when successful so it could be used to only raise the event when bulb is actually powered)
if (description?.startsWith("catchall:")) {
	def x = description[-4..-1]

    switch (x) 
    {
        case "1000":
        	result = createEvent(name: "switch", value: "off")
            break


        case "1001":
        	result = createEvent(name: "switch", value: "on")
            break

        //case "0000":
        //	result = createEvent(name: "switch", value: "off")
        //  break

        //case "0100":
        //	result = createEvent(name: "switch", value: "on")
        //  break

    }
}

else if (description?.startsWith("read attr")) {
	def i = Math.round(convertHexToInt(description[-2..-1]) / 256 * 100 )
    //log.debug "Parse level {$i}" 
	result = createEvent( name: "level", value: i )
    sendEvent( name: "switch.setLevel", value: i) //added to help subscribers
}

else if (description?.startsWith("on/off:")) {
	//Does the same as sendEvent(name: "switch", value: "on") but why?
    //Could be more for the benefit of the simulation
    //According to Sticks18 this often gets sent when a GE bulb is manually turned on
    //This never seems `to happen for the Hue Lux
	def value = description?.endsWith(" 1") ? "on" : "off"
	result = createEvent(name: "switch", value: value)
}

//log.debug "parse returned ${result?.descriptionText}"
return result
}

def on() {
// Raise an event to let subscribers know and then tell the device
log.trace "on()"
sendEvent(name: "switch", value: "on")
"st cmd 0x${device.deviceNetworkId} ${endpointId} 6 1 {}"
}

def off() {
// Raise an event to let subscribers know and then tell the device
log.trace "off()"
sendEvent(name: "switch", value: "off")
"st cmd 0x${device.deviceNetworkId} ${endpointId} 6 0 {}"
}

def refresh() {
//Ask the device to send values for the switch and level
log.trace "refresh()"
[
"st rattr 0x${device.deviceNetworkId} ${endpointId} 6 0",
"delay 500",
"st rattr 0x${device.deviceNetworkId} ${endpointId} 8 0"
]
}

def poll(){
log.trace "poll()"
refresh()
}

def setLevel(value) {
log.trace "setLevel($value)"
def cmds = []

// If level set to 0 then raise a switch=off event and tell the device
if (value == 0) {
	sendEvent(name: "switch", value: "off")
	cmds << "st cmd 0x${device.deviceNetworkId} ${endpointId} 6 0 {}"
}

// If the level is greater than 0 and the switch is off then raise a switch=on event 
else if (device.latestValue("switch") == "off") {
	sendEvent(name: "switch", value: "on")
}

// Raise a level=value event and tell the device
sendEvent(name: "level", value: value)
sendEvent( name: "switch.setLevel", value: value) //added to help subscribers
def level = new BigInteger(Math.round(value * 255 / 100).toString()).toString(16)
cmds << "st cmd 0x${device.deviceNetworkId} ${endpointId} 8 4 {${level} 0000}"

//log.debug cmds
cmds
}

/*
//Doesn't seem to be called and doesn't appear to be necessary
def configure() {
log.trace "configure()"

log.debug "Confuguring Reporting and Bindings."
def configCmds = [	

    //Switch Reporting
    "zcl global send-me-a-report 6 0 0x10 0 3600 {01}", "delay 500",
    "send 0x${device.deviceNetworkId} 1 1", "delay 1000",

    //Level Control Reporting
    "zcl global send-me-a-report 8 0 0x20 5 3600 {0010}", "delay 200",
    "send 0x${device.deviceNetworkId} 1 1", "delay 1500",

    "zdo bind 0x${device.deviceNetworkId} 1 1 6 {${device.zigbeeId}} {}", "delay 1000",
	"zdo bind 0x${device.deviceNetworkId} 1 1 8 {${device.zigbeeId}} {}", "delay 500",
]
return configCmds + refresh() // send refresh cmds as part of config
}
*/

private getEndpointId() {
new BigInteger(device.endpointId, 16).toString()
}

private hex(value, width=2) {
def s = new BigInteger(Math.round(value).toString()).toString(16)
while (s.size() < width) {
s = "0" + s
}
s
}

private Integer convertHexToInt(hex) {
Integer.parseInt(hex,16)
}
