$(document).ready(function() {
  var id;
  var metric;
  var timer;
  var width = 800;
  var height = 400;
  var margin = 30;

  // calculate graph location
  function setPosition(loc) {
    this.up = 0;
    this.right = (loc.left + width > screen.availWidth) ? screen.availWidth - (loc.left + width) : -50;
    this.left = 0;
    this.down = (loc.top + height > screen.availHeight) ? screen.availHeight - (loc.top + height) : -50;
  }

  // insert graph
  function loadGraph() {
    var shape = 'width=' + width + '&height=' + height + '&margin=' + margin;
    var options = '&areaMode=all&fontSize=14';
    var period = '&from=-' + (graphitePeriod || 3600) + 'seconds';
    var url = graphiteApiUrl + '/render/?' + shape + options + period + '&target=pulse.';
    var pos = new setPosition($('#' + id).position());
    var coords = pos.up + ' ' + pos.right + ' ' + pos.down + ' ' + pos.left;
    console.log('coords: ' + coords);
    var style = 'style="position: absolute; margin: ' + coords + ';"';
    $('#' + id).before('<img id="hoverGraph" src="' + url + metric + '" ' + style + '>');
  }

  // main
  $('td').hover(function() {
    id = $(this).find('span').attr('id');
    metric = id.replace(/-sparkline$/, '');
    timer = setTimeout(loadGraph, 500);
  }, function() {
    $('#hoverGraph').remove();
    clearTimeout(timer);
  });
});
