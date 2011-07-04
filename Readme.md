# Pulse

Real-time Heroku operations dashboard.


## Overview

Pulse provides web-accessible, real-time metrics for the [Heroku platform](http://http://www.heroku.com/) kernel. Heroku engineers use Pulse internally to maintain ambient awareness of Heroku's distributed infrastructure and to quickly diagnose platform issues.

Pulse works by consuming, processing, and performing statistical calculations against the platform's unified [event log stream](http://adam.heroku.com/past/2011/4/1/logs_are_streams_not_files/). All Heroku components log extensively and in an easily machine-parsable format; by processing these logs, we can build various platform metrics, analytics, monitoring, auditing, and debugging tools orthogonally to the critical-path components themselves. Pulse is one such tool:

![pulse architecture](http://s3.amazonaws.com/pulse-doc/architecture.png)

The log messages that ultimately power Pulse originate from hosts across the Heroku platform. These messages are sent to a load-balanced pool of log forwarders. The log forwarders in turn distribute messages to an internal [Splunk](http://www.splunk.com/) cluster for archival and long-term analytics, and also host "Aorta" servers which provide Pulse access to the log stream.

Pulse itself is a distributed application implemented with three [process types](http://devcenter.heroku.com/articles/process-model): `receiver`, `merger`, and `web`. Processes of the `receiver` type are responsible for physically consuming a load-balanced fraction of the platform event stream, parsing each text-encoded event into a data structure, and streaming that data through the initial phase of the Pulse statistical pipeline. The `receiver` processes periodically broadcast "stat fragments" corresponding to partial roll-ups of the various stats that Pulse tracks, for consumption by the `merger` process. To ensure that the production Pulse deployment can process the entire Heroku log stream at peak platform load, this process type is horizontally scaled.

The `merger` is a singleton processes responsible for providing a unified statistical view for the Pulse deployment. It continuously combines stat fragments as they arrive from the `receiver` processes and periodically emits the resulting "stat snapshots" for consumption by front-end clients.

The key consumers of the stats snapshot stream are processes of the `web` type. These processes consume and buffer a short history of these stat snapshots, and dump their buffer in response to web requests. Web clients use this data to display corresponding sparkline graphs.

Pulse is written in Clojure and deployed to Heroku itself using the platform's native Clojure support.


## Running locally

Ensure that Aorta is running at `AORTA_URL`, then:

    $ cp .env.sample .env
    $ mate .env
    $ source .env
    $ lein deps
    $ foreman start


## Running as Heroku app

Ensure that Aorta is running and dyno-reachable at `AORTA_URL`, then:

    $ heroku create pulse-production --stack cedar
    $ heroku addons:add redistogo:small
    $ heroku config:add REDIS_URL="..."
    $ heroku config:add AORTA_URLS="..."
    $ heroku config:add WEB_AUTH="..."
    $ heroku config:add FORCE_HTTPS="1"
    $ git push heroku master
    $ heroku scale receiver=6 merger=1 web=2


## Viewing stats graphs

    $ heroku open


## Viewing stats feed

    $ heroku config
    $ export REDIS_HOST="..."; export REDIS_PORT="..."; export REDIS_PASS="..."
    $ redis-cli -h $REDIS_HOST -p $REDIS_PORT -x $REDIS_PASS subscribe stats.merged


## See also

* [Nimrod](https://github.com/sbtourist/nimrod): Non-invasive, log-based metrics server in Clojure
* [Splunk](http://www.splunk.com/): Log visibility software
* [Loggly](http://www.loggly.com/): Logging as a service
* [Papertrail](https://papertrailapp.com): Hosted cloud log management
