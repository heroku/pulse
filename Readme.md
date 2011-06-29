# Pulse

Real-time Heroku operations dashboard.


# Running locally:

    $ cp .env.sample .env
    $ mate .env
    $ source .env
    $ lein deps
    $ foreman start


## Running as Heroku app:

    $ heroku create pulse-production --stack cedar
    $ heroku addons:add ssl:piggyback
    $ heroku addons:add redistogo:small
    $ heroku config:add AORTA_URLS="..."
    $ heroku config:add WEB_AUTH="..."
    $ git push heroku master
    $ heroku scale receiver=8 merger=1 web=3


## Viewing stats:

    $ curl -o redis-cliu https://gist.github.com/...
    $ chmod +x redis-cliu  
    $ ./redis-cliu subscribe stats
