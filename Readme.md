# Pulse

Real-time Heroku operations dashboard.


# Running locally:

Ensure that Aorta is running at `AORTA_URL`, then:

    $ cp .env.sample .env
    $ mate .env
    $ source .env
    $ lein deps
    $ foreman start


## Running as Heroku app:

Ensure that Aorta is running and dyno-reachable at `AORTA_URL`, then:

    $ heroku create pulse-production --stack cedar
    $ heroku addons:add redistogo:small
    $ heroku config:add REDIS_URL="..."
    $ heroku config:add AORTA_URLS="..."
    $ heroku config:add WEB_AUTH="..."
    $ git push heroku master
    $ heroku scale receiver=6 merger=1 web=2


## Viewing web stats graphs:

    $ heroku open


## Viewing raw stats feed:

    $ heroku config
    $ export REDIS_HOST="..."; export REDIS_PORT="..."; export REDIS_PASS="..."
    $ redis-cli -h $REDIS_HOST -p $REDIS_PORT -x $REDIS_PASS subscribe stats.merged
