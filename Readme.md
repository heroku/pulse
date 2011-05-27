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
    $ heroku config:add FORWARDER_HOSTS="..."
    $ git push heroku master
    $ heroku scale web 0 engine 5


## Viewing stats:

    $ curl -o redis-cliu https://gist.github.com/...
    $ chmod +x redis-cliu  
    $ ./redis-cliu subscribe stats

    $ clj -m pulse.term
