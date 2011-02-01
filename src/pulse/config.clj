(ns pulse.config)

(def env (System/getenv))
(def redis-url (get env "REDIS_URL"))
