$(document).ready(function() {
  var id;
  var metric;
  var timer;
  var width = 800;
  var height = 400;
  var margin = 30;

  // calculate graph location
  function setPosition(loc) {
    var fullHeight = height;
    var fullWidth = width;
    if (loc.top + fullHeight > screen.availHeight) {
      this.up = screen.availHeight - (loc.top + fullHeight + 1);
    } else {
      this.up = 0;
    }
    this.right = 0;
    this.down = 0;
    if (loc.left + fullWidth > screen.availWidth) {
      this.left = screen.availWidth - (loc.left + fullWidth + 1);
    } else {
      this.left = 0;
    }
  }

  // insert graph
  function loadGraph() {
    var shape = 'width=' + width + '&height=' + height + '&margin=' + margin;
    var options = '&areaMode=all&fontSize=14';
    var period = '&from=-' + (graphitePeriod || 3600) + 'seconds';
    var url = graphiteApiUrl + '/render/?' + shape + options + period + '&target=pulse.';
    var pos = new setPosition($('#' + id).position());
    var coords = pos.up + ' ' + pos.right + ' ' + pos.down + ' ' + pos.left;
    var style = 'style="position: absolute; margin: ' + coords + ';"';
    $('#' + id).before('<img id="hoverGraph" src="' + url + metric + '" ' + style + '>');
  }

  // main
  $('td').toggle(function() {
    id = $(this).find('span').attr('id');
    metric = id.replace(/-sparkline$/, '');
    timer = setTimeout(loadGraph, 500);
  }, function() {
    $('#hoverGraph').remove();
    clearTimeout(timer);
  });
});
