# DEPRECATED: Pulse

Real-time, streaming, distributed event -> metric computation.


## Overview

Pulse provides near-real-time, streaming, distributed event-to-metric compuation for the [Heroku platform](http://www.heroku.com/) kernel. Heroku engineers use Pulse to maintain ambient awareness of Heroku's distributed infrastructure and to quickly diagnose platform issues.

Pulse works by consuming, processing, and performing statistical calculations against the platform's unified [event log stream](http://adam.heroku.com/past/2011/4/1/logs_are_streams_not_files/). All Heroku components log extensively and in an easily machine-parsable format; by processing these logs, we can build various platform metrics, analytics, monitoring, auditing, and debugging tools orthogonally to the critical-path components themselves. Pulse is one such tool.

The log messages that ultimately power Pulse originate from hosts across the Heroku platform. These messages are sent to a load-balanced pool of log forwarders. The log forwarders in turn distribute messages to an internal [Splunk](http://www.splunk.com/) cluster for archival and long-term analytics, and also host "Aorta" servers which provide Pulse access to the log stream.

Pulse itself is a distributed application implemented with two [process types](http://devcenter.heroku.com/articles/process-model): `receiver` and `merger`. Processes of the `receiver` type are responsible for physically consuming a load-balanced fraction of the platform event stream, parsing each text-encoded event into a data structure, and streaming that data through the initial phase of the Pulse statistical pipeline. The `receiver` processes periodically broadcast "stat fragments" corresponding to partial roll-ups of the various stats that Pulse tracks, for consumption by the `merger` processes. To ensure that the production Pulse deployment can process the entire Heroku log stream at peak platform load, this process type is highly horizontally scaled.

The `merger` is a smaller set of sharded singleton processes responsible for providing a unified statistical view for the Pulse deployment. These processes continuously combine stat fragments as they arrive from the `receiver` processes and periodically emits the resulting "stat snapshots" as JSON into the given `METRICS_URLS`.

Pulse is written in Clojure and deployed to Heroku itself using the platform's native Clojure support.


## Local Deploy

Ensure that Aorta is running at `AORTA_URL`, then:

```bash
$ cp .env.sample .env
$ mate .env
$ export $(cat .env)
$ lein deps
$ foreman start
```


## Heroku Platform Deploy

Ensure that Aortas are running and dyno-reachable at `AORTA_URLS`, and that the username in the `AORTA_URLS` are scoped to this particular deployment of Pulse. Then:

```bash
$ DEPLOY=production/staging/you/etc
$ heroku create pulse-$DEPLOY -s cedar

$ heroku addons:add redistogo:large -r $DEPLOY
$ heroku config:add BUILDPACK_URL=https://github.com/heroku/heroku-buildpack-clojure.git -r $DEPLOY
$ heroku config:add DEPLOY=$DEPLOY -r $DEPLOY
$ heroku config:add CLOUDS=heroku.com -r $DEPLOY
$ heroku config:add PUBLISH_THREADS=2 -r $DEPLOY
$ heroku config:add METRICS_URL=... -r $DEPLOY
$ heroku config:add REDIS_URL=... -r $DEPLOY
$ heroku config:add AORTA_URLS=... -r $DEPLOY

$ git push $DEPLOY master
$ heroku scale receiver=60 merger0=1 merger1=1 merger2=1 merger3=1 merger4=1 emitter=1 -r $DEPLOY
```
