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

var pulseScales = {}

function pulseUpdate() {
  $.get("/stats", function(stats) {
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
        $(scalarId).html(Math.round(statVal));
      } else if ($(scalarId) != null) {
        $(sparklineId).sparkline(statBuff, pulseSparklineOpts);
        $(scalarId).html(Math.round(statVal));
      }
    }
  });
}

function pulseInit() {
  pulseUpdate();
  setInterval(pulseUpdate, 500);
}

$(document).ready(pulseInit);
