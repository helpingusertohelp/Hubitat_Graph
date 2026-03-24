/**
 * C-8 Pro Master Graphing - JSON 
 * - JSON-based storage 
 * - Crash-proof file I/O
 * - Auto-healing subscriptions
 * - Watchdog + forced flush
 * - Corrupted-entry filtering
 * - Better UI (range presets)
 * - History Manager (metadata-based, fast)
 * - Summary Dashboard
 * - Multi-attribute graphs
 * - Strict sensor filtering (only sensors logging ALL selected attributes)
 */

definition(
    name: "C-8 Pro Master Graphing JSON",
    namespace: "C8-Pro-Graphing",
    author: "Gemini-Optimized",
    description: "Graphing with JSON storage, improved UI, history manager, and multi-attribute graphs.",
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
    page(name: "historyManagerPage")
}

def mainPage() {
    if (!state.accessToken) { try { createAccessToken() } catch (e) { log.warn "Access token error: $e" } }
    if (state.savedComparisons == null) state.savedComparisons = []
    if (state.lastCleanup == null) state.lastCleanup = [:]
    if (state.eventCache == null) state.eventCache = [:]
    if (state.lastWrite == null) state.lastWrite = [:]
    if (state.lastEventTime == null) state.lastEventTime = 0
    if (state.debug == null) state.debug = false
    if (state.meta == null) state.meta = [:]   // file metadata cache

    dynamicPage(name: "mainPage", title: "Master Graphing System (JSON)", install: true, uninstall: true) {
        section("<h2 style='color:#008CBA; margin:0;'>Graph View</h2>") {
            if (state.savedComparisons.size() == 0) {
                paragraph "No graphs created yet."
            }
            state.savedComparisons.eachWithIndex { comp, idx ->
                def sType = comp.style ?: 'line'
                def fFill = comp.fill ?: false
                def cUrl = "${getFullLocalApiServerUrl()}/compare?ids=${comp.ids}&attrs=${comp.attrs}&style=${sType}&fill=${fFill}&access_token=${state.accessToken}"
                def title = "<b>${comp.name}</b>"
                def desc = "Click to view ${comp.attrs ?: 'data'} graph"
                href url: cUrl, style: "external", title: title, description: desc
            }
        }

        section("Settings & Data Management") {
            href name: "toSensorConfig", page: "sensorConfigPage", title: "<b>Sensor Configuration & Optimization</b>", description: "Manage sensor logging and data history."
            href name: "toAddComparison", page: "addComparisonPage", title: "<b>+ Create New Graph</b>"
        }

        section("History & Storage") {
            href name: "toHistoryManager", page: "historyManagerPage", title: "<b>History Manager</b>", description: "View and manage stored logs (fast, metadata-based)."
        }

        section("Cleanup") {
            state.savedComparisons.eachWithIndex { comp, idx ->
                input "del_comp_${idx}", "button", title: "Delete Graph: ${comp.name}", width: 4
            }
        }

        section("Advanced / Debug") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false, submitOnChange: true
            state.debug = debugLogging ?: false
            paragraph "Last event time: ${state.lastEventTime ? new Date(state.lastEventTime) : 'none'}"
            if (state.lastAction) {
                paragraph "<b style='color:#008CBA;'>${state.lastAction}</b>"
                state.lastAction = null
            }
        }
    }
}

def sensorConfigPage() {
    dynamicPage(name: "sensorConfigPage", title: "Sensor Configuration") {
        section("Select and Configure Sensors") {
            input "monitoredSensors", "capability.sensor", title: "Select Sensors", multiple: true, required: true, submitOnChange: true
            if (monitoredSensors) {
                monitoredSensors.sort { it.displayName }.each { dev ->
                    def hasAnyFile = false
                    def attrOptions = [:]
                    def supported = dev.supportedAttributes.collect { it.name }.unique().sort()
                    supported.each { aName ->
                        def exists = false
                        try {
                            def fn = "graph_${dev.id}_${aName}.json"
                            def raw = safeDownload(fn)
                            if (raw) {
                                exists = true
                                hasAnyFile = true
                            }
                        } catch (e) { }
                        attrOptions.put(aName, exists ? "${aName} (found history)" : "${aName}")
                    }

                    def devLabel = hasAnyFile ? ("${dev.displayName} <b style='color:green;'>(data found)</b>") : "${dev.displayName}"
                    paragraph("<br><b style='font-size:16px;'>${devLabel}</b>")

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
            input "newCompStyle", "enum", title: "Chart Style", options: ["line": "Line Chart (Dots)", "bar": "Bar Chart (Solid)"], defaultValue: "line", required: true, submitOnChange: true
            if (newCompStyle == "bar") {
                input "fillBars", "bool", title: "Solid Connection Logic?", defaultValue: true
            }
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
                input "newCompAttrs", "enum", title: "Attributes (Active Logs Only)", options: activeAttributes, multiple: true, required: true, submitOnChange: true
            } else {
                paragraph("<b style='color:red;'>No data is being captured yet.</b>")
            }

            if (newCompAttrs && monitoredSensors) {
                def validSensors = [:]
                monitoredSensors.each { dev ->
                    def loggedAttrs = settings["attr_${dev.id}"]
                    if (loggedAttrs) {
                        if (newCompAttrs.every { loggedAttrs.contains(it) }) {
                            validSensors[dev.id] = dev.displayName
                        }
                    }
                }

                if (validSensors) {
                    input "newCompSensors", "enum", title: "Sensors (Logging ALL Selected Attributes)",
                          options: validSensors, multiple: true, required: true
                } else {
                    paragraph "<b style='color:red;'>No sensors log all selected attributes.</b>"
                }
            }

            input "saveCompBtn", "button", title: "SAVE GRAPH", width: 4

            if (state.lastAction) {
                paragraph("<br><b style='color:#008CBA;'>${state.lastAction}</b>")
                state.lastAction = null
            }
        }
    }
}

def historyManagerPage() {
    dynamicPage(name: "historyManagerPage", title: "History Manager") {
        section("Overview") {
            paragraph "Fast, metadata-based view of stored logs. No full file reads."
        }

        if (!monitoredSensors) {
            section { paragraph "No monitored sensors configured yet." }
            return
        }

        // Summary dashboard table
        section("<b>Summary Dashboard</b>") {
            def rows = []
            monitoredSensors.sort { it.displayName }.each { dev ->
                def attrs = settings["attr_${dev.id}"]
                if (!attrs) return
                attrs.each { aName ->
                    def fileName = "graph_${dev.id}_${aName}.json"
                    def meta = state.meta[fileName] ?: [:]
                    def count = meta.count ?: 0
                    def oldest = meta.oldest ? new Date(meta.oldest as long) : null
                    def newest = meta.newest ? new Date(meta.newest as long) : null
                    def lastWrite = meta.lastWrite ? new Date(meta.lastWrite as long) : null

                    def status = "OK"
                    if (!meta || count == 0) status = "No data"
                    else {
                        long ageMs = now() - (meta.newest ?: 0L)
                        if (ageMs > 6 * 3600000L) status = "Stale"
                    }

                    rows << [
                        devName   : dev.displayName,
                        attr      : aName,
                        count     : count,
                        oldest    : oldest,
                        newest    : newest,
                        lastWrite : lastWrite,
                        status    : status,
                        fileName  : fileName,
                        devId     : dev.id
                    ]
                }
            }

            if (rows) {
                def html = new StringBuilder()
                html << "<table style='width:100%; border-collapse:collapse; font-size:12px;'>"
                html << "<tr style='background:#222; color:#eee;'>"
                html << "<th style='border:1px solid #444; padding:4px;'>Device</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Attribute</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Entries</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Oldest</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Newest</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Last Write</th>"
                html << "<th style='border:1px solid #444; padding:4px;'>Status</th>"
                html << "</tr>"

                rows.each { r ->
                    def color = "#0f0"
                    if (r.status == "No data") color = "#f33"
                    else if (r.status == "Stale") color = "#ffb300"

                    html << "<tr>"
                    html << "<td style='border:1px solid #444; padding:4px;'>${r.devName}</td>"
                    html << "<td style='border:1px solid #444; padding:4px;'>${r.attr}</td>"
                    html << "<td style='border:1px solid #444; padding:4px; text-align:right;'>${r.count}</td>"
                    html << "<td style='border:1px solid #444; padding:4px;'>${r.oldest ?: 'n/a'}</td>"
                    html << "<td style='border:1px solid #444; padding:4px;'>${r.newest ?: 'n/a'}</td>"
                    html << "<td style='border:1px solid #444; padding:4px;'>${r.lastWrite ?: 'n/a'}</td>"
                    html << "<td style='border:1px solid #444; padding:4px; color:${color}; font-weight:bold;'>${r.status}</td>"
                    html << "</tr>"
                }

                html << "</table>"
                paragraph html.toString()
            } else {
                paragraph "No metadata available yet. Once events are logged, this table will populate."
            }
        }

        // Per-device purge controls (still needed)
        monitoredSensors.sort { it.displayName }.each { dev ->
            def attrs = settings["attr_${dev.id}"]
            if (!attrs) return
            section("<b>${dev.displayName}</b>") {
                attrs.each { aName ->
                    def fileName = "graph_${dev.id}_${aName}.json"
                    def meta = state.meta[fileName] ?: [:]
                    def count = meta.count ?: 0
                    paragraph "<b>${aName}:</b> Entries: ${count}"
                    input "purge_${dev.id}_${aName}", "button", title: "Purge ${aName} history", width: 4
                }
            }
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "saveCompBtn") {
        if (settings.newCompName && settings.newCompSensors && settings.newCompAttrs) {
            def sIds = (settings.newCompSensors instanceof List) ? settings.newCompSensors.join(",") : settings.newCompSensors
            def aList = (settings.newCompAttrs instanceof List) ? settings.newCompAttrs.join(",") : settings.newCompAttrs
            def currentComps = state.savedComparisons ?: []
            currentComps << [
                name : settings.newCompName,
                attrs: aList,
                ids  : sIds,
                style: settings.newCompStyle,
                fill : (settings.fillBars ?: false)
            ]
            state.savedComparisons = currentComps
            state.lastAction = "Graph '${settings.newCompName}' saved! Click 'Done' to return to the main page."
        }
    }
    if (btn?.startsWith("del_comp_")) {
        def idx = btn.split("_")[-1].toInteger()
        def currentComps = state.savedComparisons
        if (idx >= 0 && idx < currentComps.size()) {
            currentComps.remove(idx)
            state.savedComparisons = currentComps
            state.lastAction = "Graph removed."
        }
    }
    if (btn?.startsWith("purge_")) {
        def parts = btn.split("_")
        if (parts.size() >= 3) {
            def devId = parts[1]
            def attr = parts[2]
            def fileName = "graph_${devId}_${attr}.json"
            try {
                uploadHubFile(fileName, "[]".getBytes("UTF-8"))
                state.eventCache[fileName] = []
                state.meta[fileName] = [count: 0, oldest: null, newest: null, lastWrite: now()]
                log.warn "Purged history for ${fileName}"
                state.lastAction = "Purged history for device ${devId}, attribute ${attr}."
            } catch (e) {
                log.error "Error purging ${fileName}: $e"
                state.lastAction = "Error purging history for device ${devId}, attribute ${attr}."
            }
        }
    }
}

def updated() { initialize() }
def installed() { initialize() }

def initialize() {
    if (state.debug) log.debug "Initializing app..."
    unsubscribe()
    unschedule()

    if (state.meta == null) state.meta = [:]

    monitoredSensors?.each { dev ->
        def attrs = settings["attr_${dev.id}"]
        if (attrs) {
            attrs.each { attr ->
                if (state.debug) log.debug "Subscribing to ${dev.displayName} - ${attr}"
                subscribe(dev, attr, handler)
            }
        }
    }

    runEvery5Minutes("healthCheck")
    runEvery1Hour("forceFlush")
}

def handler(evt) {
    try {
        if (evt.value == null || !evt.value.toString().isNumber()) return
        state.lastEventTime = now()
        def fileName = "graph_${evt.deviceId}_${evt.name}.json"

        if (state.eventCache[fileName] == null) state.eventCache[fileName] = []
        state.eventCache[fileName] << [now(), evt.value.toBigDecimal()]

        def userMaxCount = (settings["batchCount_${evt.deviceId}"] ?: 100) as int
        def userMaxHours = (settings["batchTime_${evt.deviceId}"] ?: 1) as int
        def lastWriteTime = (state.lastWrite[fileName] ?: 0L) as long

        if (state.debug) log.debug "Event for ${fileName}: cache size=${state.eventCache[fileName].size()}, lastWrite=${lastWriteTime}"

        if (state.eventCache[fileName].size() >= userMaxCount ||
            (now() - lastWriteTime > (userMaxHours * 3600000L))) {
            commitToStorage(fileName, evt.deviceId)
        }
    } catch (e) {
        log.error "Error in handler: $e"
    }
}

def commitToStorage(fileName, deviceId) {
    try {
        if (state.debug) log.debug "Committing to storage: ${fileName}"

        def daysToKeep = (settings["retention_${deviceId}"] ?: 7) as int
        long cutoff = now() - (daysToKeep * 86400000L)

        def existingJson = safeDownload(fileName)
        List existingData = []
        if (existingJson) {
            try {
                existingData = parseJson(existingJson)
                if (!(existingData instanceof List)) existingData = []
            } catch (e) {
                log.warn "Corrupted JSON in ${fileName}, resetting. Error: $e"
                existingData = []
            }
        }

        def newEntries = state.eventCache[fileName] ?: []
        List combined = []
        combined.addAll(existingData)
        combined.addAll(newEntries)

        combined = combined.findAll { row ->
            try {
                if (!(row instanceof List) || row.size() != 2) return false
                def ts = row[0] as long
                def val = row[1]
                return ts > cutoff && val != null && val.toString().isNumber()
            } catch (e) {
                return false
            }
        }

        int maxEntries = 50000
        if (combined.size() > maxEntries) {
            combined = combined.sort { it[0] }.takeRight(maxEntries)
        }

        def jsonOut = groovy.json.JsonOutput.toJson(combined)
        uploadHubFile(fileName, jsonOut.getBytes("UTF-8"))

        state.eventCache[fileName] = []
        state.lastWrite[fileName] = now()

        // Update metadata cache
        if (!state.meta) state.meta = [:]
        if (combined && combined.size() > 0) {
            def oldest = combined[0][0] as long
            def newest = combined[-1][0] as long
            state.meta[fileName] = [
                count    : combined.size(),
                oldest   : oldest,
                newest   : newest,
                lastWrite: state.lastWrite[fileName]
            ]
        } else {
            state.meta[fileName] = [
                count    : 0,
                oldest   : null,
                newest   : null,
                lastWrite: state.lastWrite[fileName]
            ]
        }
    } catch (e) {
        log.error "Error in commitToStorage(${fileName}): $e"
    }
}

def forceFlush() {
    if (state.debug) log.debug "Force flush triggered"
    state.eventCache?.each { fileName, list ->
        if (list && list.size() > 0) {
            def parts = fileName.split("_")
            if (parts.size() >= 2) {
                def devIdPart = parts[1]
                def devId = devIdPart.replaceAll("\\D", "")
                if (devId) {
                    commitToStorage(fileName, devId)
                }
            }
        }
    }
}

def healthCheck() {
    try {
        long nowTs = now()
        long last = (state.lastEventTime ?: 0L) as long
        if (state.debug) log.debug "Health check: lastEventTime=${last}, now=${nowTs}"

        if (last == 0L || (nowTs - last > 600000L)) {
            log.warn "No events detected in >10 minutes, reinitializing subscriptions..."
            initialize()
        }
    } catch (e) {
        log.error "Error in healthCheck: $e"
    }
}

private String safeDownload(String fileName) {
    try {
        def raw = downloadHubFile(fileName)
        if (raw == null) return ""
        return new String(raw, "UTF-8")
    } catch (e) {
        if (state.debug) log.debug "safeDownload(${fileName}) failed: $e"
        return ""
    }
}

mappings {
    path("/compare") { action: [GET: "renderChart"] }
}

def renderChart() {
    def ids = params.ids?.split(",")
    def attrs = params.attrs?.split(",")?.findAll { it } ?: []
    def chartStyle = params.style ?: "line"
    def isFilled = (params.fill == "true")
    def startStr = params.start ?: ""
    def endStr = params.end ?: ""

    long startTs = startStr ? Date.parse("yyyy-MM-dd", startStr).time : (now() - 86400000)
    long endTs = endStr ? Date.parse("yyyy-MM-dd", endStr).time + 86399999 : now()

    def displayStart = startStr ?: new Date(now() - 86400000).format("yyyy-MM-dd")
    def displayEnd = endStr ?: new Date().format("yyyy-MM-dd")
    def displayTitle = attrs ? attrs.join(" + ") : "Data"

    def columns = "data.addColumn('date', 'Time');"
    def masterMap = [:]
    def seriesIndex = 0

    ids?.each { id ->
        attrs.each { attrName ->
            def devName = monitoredSensors?.find { it.id == id.toString() }?.displayName ?: "Device ${id}"
            def seriesLabel = "${devName} - ${attrName}"
            columns += "data.addColumn('number', '${seriesLabel}');"
            def currentSeries = seriesIndex
            seriesIndex++

            try {
                def fileName = "graph_${id}_${attrName}.json"
                def json = safeDownload(fileName)
                List stored = []
                if (json) {
                    try {
                        stored = parseJson(json)
                        if (!(stored instanceof List)) stored = []
                    } catch (e) {
                        log.warn "Corrupted JSON in ${fileName} during render: $e"
                        stored = []
                    }
                }

                def cachedItems = state.eventCache[fileName] ?: []
                List allRows = []
                allRows.addAll(stored)
                allRows.addAll(cachedItems)

                allRows.each { row ->
                    try {
                        if (!(row instanceof List) || row.size() != 2) return
                        long ts = (row[0] as long)
                        def val = row[1]
                        if (ts >= startTs && ts <= endTs && val != null && val.toString().isNumber()) {

                            if (!masterMap[ts]) masterMap[ts] = new Object[seriesIndex]

                            def arr = masterMap[ts]

                            if (arr.length < seriesIndex) {
                                def newArr = new Object[seriesIndex]
                                for (int i = 0; i < arr.length; i++) {
                                    newArr[i] = arr[i]
                                }
                                arr = newArr
                                masterMap[ts] = arr
                            }

                            arr[currentSeries] = val
                        }
                    } catch (e) { }
                }
            } catch (e) {
                log.error "Error rendering data for id=${id}, attr=${attrName}: $e"
            }
        }
    }

    def rowString = masterMap.sort().collect { ts, vals ->
        def list = (0..<seriesIndex).collect { idx ->
            (vals && idx < vals.length && vals[idx] != null) ? vals[idx] : 'null'
        }
        return "[new Date(${ts}), ${list.join(",")}]"
    }.join(",")

    def gClass = (chartStyle == "bar" && isFilled) ? "SteppedAreaChart" : (chartStyle == "bar" ? "ColumnChart" : "LineChart")
    def pointSizeValue = (chartStyle == 'line' ? 5 : 0)

    def html = """
    <html>
      <head>
        <script type="text/javascript" src="https://www.gstatic.com/charts/loader.js"></script>
        <style>
            body { background:#121212; color:#eee; font-family: sans-serif; margin:10px; overflow: hidden; }
            .controls { background:#222; padding:12px; border-radius:8px; margin-bottom:10px; border:1px solid #444; display: flex; justify-content: space-between; align-items: center; height: 60px;}
            .data-title { font-size: 18px; font-weight: bold; color: #008CBA; text-transform: uppercase; }
            input, select { background:#333; color:#fff; border:1px solid #555; padding:5px; border-radius:4px; }
            button { background:#008CBA; color:white; border:none; padding:8px 15px; border-radius:4px; cursor:pointer; }
            #chart_div { width: 100%; height: calc(100vh - 110px); }
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
              titleTextStyle: { color: '#ccc' }
            };
            chart = new google.visualization.${gClass}(document.getElementById('chart_div'));
            chart.draw(data, options);
          }
          window.addEventListener('resize', function() { if (chart && data && options) chart.draw(data, options); });

          function setRange(preset) {
            var now = new Date();
            var start = new Date();
            if (preset === '1h') start.setHours(now.getHours() - 1);
            if (preset === '6h') start.setHours(now.getHours() - 6);
            if (preset === '24h') start.setDate(now.getDate() - 1);
            if (preset === '7d') start.setDate(now.getDate() - 7);
            if (preset === '30d') start.setDate(now.getDate() - 30);
            var s = start.toISOString().slice(0,10);
            var e = now.toISOString().slice(0,10);
            document.getElementById('startDate').value = s;
            document.getElementById('endDate').value = e;
            document.getElementById('rangeForm').submit();
          }
        </script>
      </head>
      <body>
        <div class="controls">
            <form id="rangeForm" action="" method="get" style="display:contents;">
                <input type="hidden" name="access_token" value="${params.access_token ?: state.accessToken}">
                <input type="hidden" name="ids" value="${params.ids}">
                <input type="hidden" name="attrs" value="${params.attrs}">
                <input type="hidden" name="style" value="${params.style}">
                <input type="hidden" name="fill" value="${params.fill}">
                <div>
                  From: <input id="startDate" type="date" name="start" value="${displayStart}">
                  To: <input id="endDate" type="date" name="end" value="${displayEnd}">
                </div>
                <div class="data-title">${displayTitle}</div>
                <div>
                  <select onchange="if(this.value) setRange(this.value);">
                    <option value="">Range</option>
                    <option value="1h">Last 1h</option>
                    <option value="6h">Last 6h</option>
                    <option value="24h">Last 24h</option>
                    <option value="7d">Last 7d</option>
                    <option value="30d">Last 30d</option>
                  </select>
                  <button type="submit">Update</button>
                </div>
            </form>
        </div>
        <div id="chart_div"></div>
      </body>
    </html>
    """

    render contentType: "text/html", data: html
}
