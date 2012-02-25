$(document).ready(function() {
  var id;
  var metric;
  var timer;
  var width = 800;
  var height = 400;
  var margin = 30;

  // calculate graph location
  function setPosition(coords) {
    this.up: 0,
    this.right: function() {
      if (coords.left + width > availWidth) {
        return availWidth - (coords.left + width);
      } else {
        return -50;
      }
    }
    this.left: 0,
    this.down: function() {
      if (coords.top + height > availHeight) {
        return availHeight - (coords.top + height);
      } else {
        return -50;
      }
    }
  }

  // insert graph
  function loadGraph() {
    var shape = = 'width=' + width + '&height=' + height + '&margin=' + margin;
    var options = '&areaMode=all&fontSize=14';
    var period = '&from=-' + (graphitePeriod || 3600) + 'seconds';
    var url = graphiteApiUrl + '/render/?' + shape + options + period + '&target=pulse.';
    var pos = new setPosition($('#' + id).position());
    var coords = pos.up + ' ' + pos.right + ' ' + pos.down + ' ' + pos.left;
    var style = 'style="position: absolute; margin: ' coords + ';"';
    $('#' + id).before('<img id="hoverGraph" src="' + url + metric + '" ' + style + '>');
    console.log("opening " + url + metric);
  }

  // main
  $('td').hover(function() {
    id = $(this).find('span').attr('id');
    metric = id.replace(/-sparkline$/, '');
    timer = setTimeout(loadGraph, 500);
    console.log(id, metric, timer);
  }, function() {
    $('#hoverGraph').remove();
    clearTimeout(timer);
    console.log("killing " + timer + ", closing window");
  });
});
