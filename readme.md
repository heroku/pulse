# Pulse

Real-time Heroku operations dashboard.

## Running `stat` producer process in EC2:

    $ bin/shell
    $ clj -m pulse.stat

## Subscribing to raw stats feed:

    $ export REDIS_URL=<redis-url>
    $ redis-cliu subscribe stats

## Running `term` consumer process locally:

    $ export REDIS_URL=<redis-url>
    $ clj -m pulse.term

## Running web app locally:

    $ lein deps
    $ foreman start -c web=1

## Running web app on Heroku:

    $ heroku create --stack cedar
    $ heroku addons:add redistogo:small
    $ heroku routes:create
    $ heroku config:add FORWARDER_HOSTS=$FORWARDER_HOSTS LOGPLEX_HOST=$LOGPLEX_HOSTS
    $ heroku config:add WEBSOCKET_URL=ws://<route-ip>:<route-port>/stats
    $ git push heroku master
    $ heroku scale web 1
    $ heroku scale sock 1
