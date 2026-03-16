/**
 * C-8 Pro Master Graphing - V3.4.1
 * Focus: Time-based batching with individual sensor retention.
 * Logic: Merges RAM cache with File storage for real-time accurate graphs.
 */
definition(
    name: "C-8 Pro Master Graphing",
    namespace: "C8-Pro-Graphing",
    author: "Gemini-Optimized",
    description: "Efficient graphing with RAM-to-Storage batching and data pruning.",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: true,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    if(!state.accessToken) { try { createAccessToken() } catch (e) { } }
    if(state.savedComparisons == null) state.savedComparisons = []
    if(state.lastCleanup == null) state.lastCleanup = [:]
    if(state.eventCache == null) state.eventCache = [:]
    if(state.lastWrite == null) state.lastWrite = [:]

    dynamicPage(name: "mainPage", title: "Master Graphing System", install: true, uninstall: true) {
        section("<h2 style='color:#008CBA; margin:0;'>Graph View</h2>") {
            state.savedComparisons.eachWithIndex { comp, idx ->
                def sType = comp.style ?: 'line'
                def fFill = comp.fill ?: false
                def cUrl = "${getFullLocalApiServerUrl()}/compare?ids=${comp.ids}&attr=${comp.attr}&style=${sType}&fill=${fFill}&access_token=${state.accessToken}"
                href url: cUrl, style: "external", title: "<b>${comp.name}</b>", description: "View ${comp.attr} history"
            }
        }

        section("Sensor Configuration & Optimization") {
            input "monitoredSensors", "capability.sensor", title: "Select Sensors", multiple: true, required: true, submitOnChange: true
            if (monitoredSensors) {
                monitoredSensors.sort{it.displayName}.each { dev ->
                    paragraph "<br><b style='font-size:16px;'>${dev.displayName}</b>"
                    
                    input "retention_${dev.id}", "number", title: "Days to Keep", defaultValue: 7, required: true, width: 4
                    input "batchTime_${dev.id}", "number", title: "Write every X mins", defaultValue: 60, required: true, width: 4
                    input "batchCount_${dev.id}", "number", title: "OR after X events", defaultValue: 100, required: true, width: 4
                    
                    def attrOptions = dev.supportedAttributes.collect { it.name }.unique().sort()
                    input "attr_${dev.id}", "enum", title: "Attributes to Log", options: attrOptions, multiple: true, submitOnChange: true
                }
            }
        }
        
        section("Graph Setup") {
            input "newCompName", "text", title: "Chart Name"
            input "newCompAttr", "enum", title: "Attribute", options: ["power", "temperature", "humidity", "energy", "voltage", "illuminance", "acceleration", "contact"]
            if (newCompAttr && monitoredSensors) {
                def validSensors = monitoredSensors.findAll { settings["attr_${it.id}"]?.contains(newCompAttr) }.collectEntries { [it.id, it.displayName] }
                input "newCompSensors", "enum", title: "Sensors", options: validSensors, multiple: true
            }
            input "saveCompBtn", "button", title: "Add Graph"
        }

        section("Cleanup") {
             state.savedComparisons.eachWithIndex { comp, idx -> 
                 input "del_comp_${idx}", "button", title: "Delete: ${comp.name}", width: 4 
             }
        }
    }
}

def appButtonHandler(btn) {
    if (btn == "saveCompBtn") {
        if (newCompName && newCompSensors) {
            def sIds = (newCompSensors instanceof List) ? newCompSensors.join(",") : newCompSensors
            state.savedComparisons << [name: newCompName, attr: newCompAttr, ids: sIds, style: "line", fill: false]
        }
    }
    if (btn.startsWith("del_comp_")) {
        state.savedComparisons.remove(btn.split("_")[-1].toInteger())
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
    def userMaxMinutes = settings["batchTime_${evt.deviceId}"] ?: 60
    def lastWriteTime = state.lastWrite[fileName] ?: 0
    
    // Check if we hit the time limit or the safety count limit
    if (state.eventCache[fileName].size() >= userMaxCount || (now() - lastWriteTime > (userMaxMinutes * 60000))) {
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
    
    // Prune logic (Every 24h per file)
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
          function drawChart() {
            var data = new google.visualization.DataTable();
            ${columns}
            data.addRows([${rowString}]);
            var options = {
              backgroundColor: '#121212',
              chartArea: {width: '90%', height: '80%'},
              legend: { position: 'bottom', textStyle: {color: '#ccc'} },
              hAxis: { textStyle: {color: '#ccc'}, gridlines: {color: '#333'}, format: 'MMM dd, HH:mm' },
              vAxis: { textStyle: {color: '#ccc'}, gridlines: {color: '#333'} },
              interpolateNulls: true,
              pointSize: 5,
              colors: ['#3366cc', '#dc3912', '#ff9900', '#109618']
            };
            var chart = new google.visualization.LineChart(document.getElementById('chart_div'));
            chart.draw(data, options);
          }
        </script>
      </head>
      <body>
        <div class="controls">
            <form action="" method="get" style="display:contents;">
                <input type="hidden" name="access_token" value="${params.access_token ?: state.accessToken}">
                <input type="hidden" name="ids" value="${params.ids}">
                <input type="hidden" name="attr" value="${params.attr}">
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
