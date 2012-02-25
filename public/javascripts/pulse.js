function pulseGet(apiUrl, fun, done) {
  jQuery.ajax({
    url: apiUrl.replace(/\:\/\/.*\@/, "://"),
    type: "GET",
    dataType: "json",
    beforeSend: function(xhr) {
      xhr.setRequestHeader("Authorization", "Basic " + btoa(apiUrl.match(/\:\/\/(.*)\@/, "://")[1])); },
    success: function(data, status, xhr) {
      fun(data); },
    complete: function(xhr, status) {
      done();
    }});
}

var pulseSparklineOpts = {
  chartRangeMin: 0,
  spotColor: false,
  minSpotColor: false,
  maxSpotColor: false,
  lineWidth: 2,
  width: 190,
  height: 70};

var pulseGreen =  {lineColor: "61B867", fillColor: "D7FCD9"};
var pulseOrange = {lineColor: "E3AA5B", fillColor: "FFE9C9"};
var pulseRed =    {lineColor: "CF4F48", fillColor: "FAC8C5"};

function pulseMerge(objA, objB) {
  objM = {};
  for (attrName in objA) { objM[attrName] = objA[attrName]; }
  for (attrName in objB) { objM[attrName] = objB[attrName]; }
  return objM;
}

var requestId = 0;

function pulseUpdate() {
  var thisRequestId = requestId;
  requestId++;
  //console.log("at=request request_id=" + thisRequestId);
  pulseGet(pulseApiUrl,
    function(stats) {
      //console.log("at=response request_id=" + thisRequestId);
      for (var statName in stats) {
        var statBuff = stats[statName];
        var statVal = statBuff[statBuff.length - 1];
        var scalarId = "#" + statName + "-scalar";
        var sparklineId = "#" + statName + "-sparkline";
        var statScale = pulseScales[statName];
        if (statScale != undefined) {
          var statOrange = statScale[0];
          var statRed = statScale[1];
          var statCeil = statScale[2];
          var statMax = Math.max(statVal, statCeil);
          var statColor = null;
          if (statVal >= statRed) {
            statColor = pulseRed;
          } else if (statVal >= statOrange) {
            statColor = pulseOrange;
          } else {
            statColor = pulseGreen;
          }
          var statOpts = pulseMerge(pulseSparklineOpts, pulseMerge({chartRangeMax: statMax}, statColor));
          $(sparklineId).sparkline(statBuff, statOpts);
          $(scalarId).html(formatVal(statVal));
        } else if ($(scalarId) != null) {
          $(sparklineId).sparkline(statBuff, pulseSparklineOpts);
          $(scalarId).html(formatVal(statVal));
        }
      }
      //console.log("at=rendered request_id=" + thisRequestId);
    },
    function() {
      //console.log("at=complete request_id=" + thisRequestId);
      setTimeout(pulseUpdate, 1000);
    }
  );
}

function formatVal(value) {
  if (typeof value != "number")
    return null;

  if (value > 2 || value == 0)
    return Math.round(value);

  return value.toFixed(2);
}

function pulseInit() {
  pulseUpdate();
}

$(document).ready(pulseInit);
