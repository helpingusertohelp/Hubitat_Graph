/**
 * C-8 Pro Master Graphing - V3.2.0 (Granular Retention)
 * Added: Individual sensor retention settings to control history length per device.
 * Optimization: Per-device pruning logic to minimize hub storage wear.
 */
definition(
    name: "C-8 Pro Master Graphing",
    namespace: "C8-Pro-Graphing",
    author: "Gemini-Optimized",
    description: "Stable graphing with individual sensor data retention policies.",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: true,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
    page(name: "addComparisonPage")
}

def mainPage() {
    if(!state.accessToken) { try { createAccessToken() } catch (e) { } }
    if(state.savedComparisons == null) state.savedComparisons = []
    if(state.lastCleanup == null) state.lastCleanup = [:]

    dynamicPage(name: "mainPage", title: "Master Graphing System", install: true, uninstall: true) {
        section("<h2 style='color:#008CBA; margin:0;'>Graph View</h2>") {
            state.savedComparisons.eachWithIndex { comp, idx ->
                def sType = comp.style ?: 'line'
                def fFill = comp.fill ?: false
                def cUrl = "${getFullLocalApiServerUrl()}/compare?ids=${comp.ids}&attr=${comp.attr}&style=${sType}&fill=${fFill}&access_token=${state.accessToken}"
                href url: cUrl, style: "external", title: "<b>${comp.name}</b>", description: "View ${comp.attr} history"
            }
        }

        section("Configuration & Retention") {
            input "monitoredSensors", "capability.sensor", title: "Select Sensors to Record", multiple: true, required: true, submitOnChange: true
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
                        attrOptions.put(aName, exists ? "${aName} (FOUND HISTORY)" : "${aName}")
                    }
                    
                    def devLabel = hasAnyFile ? "${dev.displayName} <b style='color:red;'>(DATA FOUND)</b>" : "${dev.displayName}"
                    paragraph "<br><b>${devLabel}</b>"
                    
                    // NEW: Per-Device Retention Setting
                    input "retention_${dev.id}", "number", title: "Days to keep ${dev.displayName} data", defaultValue: 7, required: true, width: 6
                    input "attr_${dev.id}", "enum", title: "Attributes to log", options: attrOptions, multiple: true, submitOnChange: true, width: 6
                }
            }
        }
        
        section { href name: "toAddComparison", page: "addComparisonPage", title: "<b>+ Create New Graph</b>" }
        section("Cleanup") {
             state.savedComparisons.eachWithIndex { comp, idx -> 
                 input "del_comp_${idx}", "button", title: "Delete: ${comp.name}", width: 4 
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
            input "newCompAttr", "enum", title: "Attribute", options: ["power", "temperature", "humidity", "energy", "voltage", "illuminance", "acceleration", "contact"], required: true, submitOnChange: true
            if (newCompAttr && monitoredSensors) {
                def validSensors = monitoredSensors.findAll { settings["attr_${it.id}"]?.contains(newCompAttr) }.collectEntries { [it.id, it.displayName] }
                if (validSensors) {
                    input "newCompSensors", "enum", title: "Sensors", options: validSensors, multiple: true, required: true
                } else {
                    paragraph "No sensors are currently recording '${newCompAttr}'."
                }
            }
            input "saveCompBtn", "button", title: "Save & Return"
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "saveCompBtn") {
        if (newCompName && newCompSensors) {
            def sIds = (newCompSensors instanceof List) ? newCompSensors.join(",") : newCompSensors
            state.savedComparisons << [name: newCompName, attr: newCompAttr, ids: sIds, style: newCompStyle, fill: (fillBars ?: false)]
        }
    }
    if (btn.startsWith("del_comp_")) {
        int idx = btn.split("_")[-1].toInteger()
        state.savedComparisons.remove(idx)
    }
}

def updated() { initialize() }
def installed() { initialize() }
def initialize() {
    unsubscribe()
    monitoredSensors?.each { dev -> 
        settings["attr_${dev.id}"]?.each { attr -> 
            subscribe(dev, attr, handler) 
        } 
    }
}

def handler(evt) {
    if (evt.value == null || !evt.value.toString().isNumber()) return
    def fileName = "graph_${evt.deviceId}_${evt.name}.csv"
    def entry = "${now()},${evt.value}\n"
    
    // RETENTION: Lookup device-specific setting or default to 7
    def daysToKeep = settings["retention_${evt.deviceId}"] ?: 7
    long cutoff = now() - (daysToKeep * 86400000L)
    
    def existing = ""
    try { existing = new String(downloadHubFile(fileName)) } catch (e) { }

    // Prune logic triggered every 24 hours per file
    def lastClean = state.lastCleanup[fileName] ?: 0
    if (now() - lastClean > 86400000L) {
        def lines = existing.split("\n")
        def cleanedData = lines.findAll { line ->
            def parts = line.split(",")
            return parts.size() == 2 && parts[0].isLong() && parts[0].toLong() > cutoff
        }.join("\n")
        
        uploadHubFile(fileName, (cleanedData + "\n" + entry).getBytes())
        state.lastCleanup[fileName] = now()
    } else {
        uploadHubFile(fileName, (existing + entry).getBytes())
    }
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
    
    def columns = "data.addColumn('date', 'Time');"
    def masterMap = [:] 

    ids?.eachWithIndex { id, idx ->
        def devName = monitoredSensors?.find{it.id == id.toString()}?.displayName ?: "Device ${id}"
        columns += "data.addColumn('number', '${devName}');"
        try {
            def csv = new String(downloadHubFile("graph_${id}_${attr}.csv"))
            csv.split("\n").each { line ->
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

    def html = """
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
              pointSize: ${chartStyle == 'line' ? 5 : 0},
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
                <div>From: <input type="date" name="start" value="${startStr ?: new Date(now()-86400000).format("yyyy-MM-dd")}"></div>
                <div class="data-title">${attr}</div>
                <div>To: <input type="date" name="end" value="${endStr ?: new Date().format("yyyy-MM-dd")}">
                <button type="submit">Update</button></div>
            </form>
        </div>
        <div id="chart_div"></div>
      </body>
    </html>
    """
    render contentType: "text/html", data: html
}
