$(document).ready(function() {
  var id;
  var metric;
  var timer;
  var period = graphitePeriod || 3600;
  var options = 'width=800&height=400&margin=30&areaMode=all&fontSize=14&from=-' + period + 'seconds';
  var url = graphiteApiUrl + '/render/?' + options + '&target=pulse.';
  function loadGraph() {
    var style = 'style="position: absolute; margin: -50px 0 0 -150px;"';
    $('#' + id).before('<img id="hoverGraph" src="' + url + metric + '" ' + style + '>');
    console.log("opening " + url + metric);
  }
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
