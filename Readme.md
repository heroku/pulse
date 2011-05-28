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
    $ heroku addons:upgrade logging:advanced
    $ heroku addons:upgrade releases:advanced
    $ heroku config:add AORTA_URLS="..."
    $ git push heroku master
    $ heroku scale receiver 5 merger 1 web 2


## Viewing stats:

    $ curl -o redis-cliu https://gist.github.com/...
    $ chmod +x redis-cliu  
    $ ./redis-cliu subscribe stats

    $ lein run -m pulse.term
