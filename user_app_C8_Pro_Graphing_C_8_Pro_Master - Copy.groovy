/**
 * C-8 Pro Master Graphing - V1
 */
definition(
    name: "C-8 Pro Master Graphing",
    namespace: "C8-Pro-Graphing",
    author: "Gemini-Optimized",
    description: "Graphing with fixed UI syntax and dedicated config page.",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: true,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
    page(name: "sensorConfigPage")
    page(name: "addComparisonPage")
}

def mainPage() {
    if(!state.accessToken) { try { createAccessToken() } catch (e) { } }
    if(state.savedComparisons == null) state.savedComparisons = []
    if(state.lastCleanup == null) state.lastCleanup = [:]
    if(state.eventCache == null) state.eventCache = [:]
    if(state.lastWrite == null) state.lastWrite = [:]

    dynamicPage(name: "mainPage", title: "Master Graphing System", install: true, uninstall: true) {
        section(("<h2 style='color:#008CBA; margin:0;'>Graph View</h2>")) {
            if (state.savedComparisons.size() == 0) {
                paragraph "No graphs created yet."
            }
            state.savedComparisons.eachWithIndex { comp, idx ->
                def sType = comp.style ?: 'line'
                def fFill = comp.fill ?: false
                def cUrl = "${getFullLocalApiServerUrl()}/compare?ids=${comp.ids}&attr=${comp.attr}&style=${sType}&fill=${fFill}&access_token=${state.accessToken}"
                href url: cUrl, style: "external", title: "<b>${comp.name}</b>", description: "Click to view ${comp.attr} graph"
            }
        }

        section("Settings & Data Management") {
            href name: "toSensorConfig", page: "sensorConfigPage", title: "<b>Sensor Configuration & Optimization</b>", description: "Manage sensor logging and data history."
        }
       
        section { href name: "toAddComparison", page: "addComparisonPage", title: "<b>+ Create New Graph</b>" }

        section("Cleanup") {
             state.savedComparisons.eachWithIndex { comp, idx -> 
                input "del_comp_${idx}", "button", title: "Delete Graph: ${comp.name}", width: 4 
             }
        }
    }
}

def sensorConfigPage() {
    dynamicPage(name: "sensorConfigPage", title: "Sensor Configuration") {
        section("Select and Configure Sensors") {
            input "monitoredSensors", "capability.sensor", title: "Select Sensors", multiple: true, required: true, submitOnChange: true
            if (monitoredSensors) {
                monitoredSensors.sort{it.displayName}.each { dev ->
                    def hasAnyFile = false
                    def attrOptions = [:]
                    def supported = dev.supportedAttributes.collect { it.name }.unique().sort()
                    supported.each { aName ->
                        def exists = false
                        try { 
                            if (downloadHubFile("graph_${dev.id}_${aName}.csv") != null) { 
                                exists = true
                                hasAnyFile = true
                            } 
                        } catch (e) { }
                        attrOptions.put(aName, exists ? "${aName} (found history)" : "${aName}")
                    }
                    
                    def devLabel = hasAnyFile ? ("${dev.displayName} <b style='color:green;'>(data found)</b>") : "${dev.displayName}"
                    paragraph ("<br><b style='font-size:16px;'>${devLabel}</b>")
                    
                    input "retention_${dev.id}", "number", title: "Days to Keep", defaultValue: 7, required: true, width: 4
                    input "batchTime_${dev.id}", "number", title: "Write every X hours", defaultValue: 1, required: true, width: 4
                    input "batchCount_${dev.id}", "number", title: "OR after X events", defaultValue: 100, required: true, width: 4
                    input "attr_${dev.id}", "enum", title: "Attributes to Log", options: attrOptions, multiple: true, submitOnChange: true
                }
            }
        }
    }
}

def addComparisonPage() {
    dynamicPage(name: "addComparisonPage", title: "Setup Graph") {
        section("Display Settings") {
            input "newCompName", "text", title: "Friendly Name", required: true
            input "newCompStyle", "enum", title: "Chart Style", options: ["line":"Line Chart (Dots)", "bar":"Bar Chart (Solid)"], defaultValue: "line", required: true, submitOnChange: true
            if (newCompStyle == "bar") { input "fillBars", "bool", title: "Solid Connection Logic?", defaultValue: true }
        }
        section("Data Source") {
            def activeAttributes = []
            monitoredSensors?.each { dev ->
                def selected = settings["attr_${dev.id}"]
                if (selected) {
                    if (selected instanceof List) activeAttributes.addAll(selected)
                    else activeAttributes.add(selected)
                }
            }
            activeAttributes = activeAttributes.unique().sort()

            if (activeAttributes.size() > 0) {
                input "newCompAttr", "enum", title: "Attribute (Active Logs Only)", options: activeAttributes, required: true, submitOnChange: true
            } else {
                paragraph ("<b style='color:red;'>No data is being captured yet.</b>")
            }

            if (newCompAttr && monitoredSensors) {
                def validSensors = monitoredSensors.findAll { settings["attr_${it.id}"]?.contains(newCompAttr) }.collectEntries { [it.id, it.displayName] }
                if (validSensors) {
                    input "newCompSensors", "enum", title: "Sensors", options: validSensors, multiple: true, required: true
                }
            }
            
            input "saveCompBtn", "button", title: "SAVE GRAPH", width: 4
            
            if (state.lastAction) {
                paragraph ("<br><b style='color:#008CBA;'>${state.lastAction}</b>")
                state.lastAction = null 
            }
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "saveCompBtn") {
        if (settings.newCompName && settings.newCompSensors) {
            def sIds = (settings.newCompSensors instanceof List) ? settings.newCompSensors.join(",") : settings.newCompSensors
            def currentComps = state.savedComparisons ?: []
            currentComps << [name: settings.newCompName, attr: settings.newCompAttr, ids: sIds, style: settings.newCompStyle, fill: (settings.fillBars ?: false)]
            state.savedComparisons = currentComps
            state.lastAction = "Graph '${settings.newCompName}' saved! Click 'Done' to return to the main page."
        }
    }
    if (btn.startsWith("del_comp_")) {
        def idx = btn.split("_")[-1].toInteger()
        def currentComps = state.savedComparisons
        currentComps.remove(idx)
        state.savedComparisons = currentComps
    }
}

def updated() { initialize() }
def installed() { initialize() }

def initialize() {
    unsubscribe()
    monitoredSensors?.each { dev -> 
        settings["attr_${dev.id}"]?.each { attr -> subscribe(dev, attr, handler) } 
    }
}

def handler(evt) {
    if (evt.value == null || !evt.value.toString().isNumber()) return
    def fileName = "graph_${evt.deviceId}_${evt.name}.csv"
    if (state.eventCache[fileName] == null) state.eventCache[fileName] = []
    state.eventCache[fileName] << "${now()},${evt.value}"
    def userMaxCount = settings["batchCount_${evt.deviceId}"] ?: 100
    def userMaxHours = settings["batchTime_${evt.deviceId}"] ?: 1
    def lastWriteTime = state.lastWrite[fileName] ?: 0
    if (state.eventCache[fileName].size() >= userMaxCount || (now() - lastWriteTime > (userMaxHours * 3600000L))) {
        commitToStorage(fileName, evt.deviceId)
    }
}

def commitToStorage(fileName, deviceId) {
    def daysToKeep = settings["retention_${deviceId}"] ?: 7
    long cutoff = now() - (daysToKeep * 86400000L)
    def existing = ""
    try { existing = new String(downloadHubFile(fileName)) } catch (e) { }
    def newEntries = state.eventCache[fileName].join("\n")
    def fullData = existing + "\n" + newEntries
    def lastClean = state.lastCleanup[fileName] ?: 0
    if (now() - lastClean > 86400000L) {
        def lines = fullData.split("\n")
        fullData = lines.findAll { line ->
            def parts = line.split(",")
            return parts.size() == 2 && parts[0].isLong() && parts[0].toLong() > cutoff
        }.join("\n")
        state.lastCleanup[fileName] = now()
    }
    uploadHubFile(fileName, fullData.getBytes())
    state.eventCache[fileName] = []
    state.lastWrite[fileName] = now()
}

mappings { path("/compare") { action: [GET: "renderChart"] } }

def renderChart() {
    def ids = params.ids?.split(",")
    def attr = params.attr
    def chartStyle = params.style ?: "line"
    def isFilled = (params.fill == "true")
    def startStr = params.start ?: ""
    def endStr = params.end ?: ""
    
    long startTs = startStr ? Date.parse("yyyy-MM-dd", startStr).time : (now() - 86400000)
    long endTs = endStr ? Date.parse("yyyy-MM-dd", endStr).time + 86399999 : now()
    
    def displayStart = startStr ?: new Date(now()-86400000).format("yyyy-MM-dd")
    def displayEnd = endStr ?: new Date().format("yyyy-MM-dd")
    
    def columns = "data.addColumn('date', 'Time');"
    def masterMap = [:] 

    ids?.eachWithIndex { id, idx ->
        def devName = monitoredSensors?.find{it.id == id.toString()}?.displayName ?: "Device ${id}"
        columns += "data.addColumn('number', '${devName}');"
        try {
            def csv = new String(downloadHubFile("graph_${id}_${attr}.csv"))
            def cachedItems = state.eventCache["graph_${id}_${attr}.csv"] ?: []
            def allLines = csv.split("\n") + cachedItems
            allLines.each { line ->
                def parts = line.split(",")
                if (parts.size() == 2 && parts[0].isLong()) {
                    long ts = parts[0].toLong()
                    if (ts >= startTs && ts <= endTs) {
                        if (!masterMap[ts]) masterMap[ts] = new Object[ids.size()]
                        masterMap[ts][idx] = parts[1]
                    }
                }
            }
        } catch (e) { }
    }
    
    def rowString = masterMap.sort().collect { ts, vals ->
        return "[new Date(${ts}), ${vals.collect{it==null?'null':it}.join(",")}]"
    }.join(",")

    def gClass = (chartStyle == "bar" && isFilled) ? "SteppedAreaChart" : (chartStyle == "bar" ? "ColumnChart" : "LineChart")
    def pointSizeValue = (chartStyle == 'line' ? 5 : 0)

    def html = ("""
    <html>
      <head>
        <script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
        <style>
            body { background:#121212; color:#eee; font-family: sans-serif; margin:10px; overflow: hidden; }
            .controls { background:#222; padding:12px; border-radius:8px; margin-bottom:10px; border:1px solid #444; display: flex; justify-content: space-between; align-items: center; height: 50px;}
            .data-title { font-size: 18px; font-weight: bold; color: #008CBA; text-transform: uppercase; }
            input { background:#333; color:#fff; border:1px solid #555; padding:5px; border-radius:4px; }
            button { background:#008CBA; color:white; border:none; padding:8px 15px; border-radius:4px; cursor:pointer; }
            #chart_div { width: 100%; height: calc(100vh - 100px); }
        </style>
        <script type="text/javascript">
          google.charts.load('current', {'packages':['corechart']});
          google.charts.setOnLoadCallback(drawChart);
          var chart, data, options;
          function drawChart() {
            data = new google.visualization.DataTable();
            ${columns}
            data.addRows([${rowString}]);
            options = {
              backgroundColor: '#121212',
              chartArea: {width: '90%', height: '80%'},
              legend: { position: 'bottom', textStyle: {color: '#ccc'} },
              hAxis: { textStyle: {color: '#ccc'}, gridlines: {color: '#333'}, format: 'MMM dd, HH:mm' },
              vAxis: { textStyle: {color: '#ccc'}, gridlines: {color: '#333'} },
              interpolateNulls: true,
              pointSize: ${pointSizeValue},
              colors: ['#3366cc', '#dc3912', '#ff9900', '#109618', '#990099', '#0099c6']
            };
            chart = new google.visualization.${gClass}(document.getElementById('chart_div'));
            chart.draw(data, options);
          }
          window.addEventListener('resize', function() { if (chart && data && options) chart.draw(data, options); });
        </script>
      </head>
      <body>
        <div class="controls">
            <form action="" method="get" style="display:contents;">
                <input type="hidden" name="access_token" value="${params.access_token ?: state.accessToken}">
                <input type="hidden" name="ids" value="${params.ids}">
                <input type="hidden" name="attr" value="${params.attr}">
                <input type="hidden" name="style" value="${params.style}">
                <input type="hidden" name="fill" value="${params.fill}">
                <div>From: <input type="date" name="start" value="${displayStart}"></div>
                <div class="data-title">${attr}</div>
                <div>To: <input type="date" name="end" value="${displayEnd}">
                <button type="submit">Update</button></div>
            </form>
        </div>
        <div id="chart_div"></div>
      </body>
    </html>
    """)
    render contentType: "text/html", data: html
}
