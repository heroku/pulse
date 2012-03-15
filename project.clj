(defproject pulse "0.0.1"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [clj-redis "0.0.12"]
                 [cheshire "3.0.0"]
                 [org.jboss.netty/netty "3.2.7.Final"]
                 [hiccup "0.3.8"]
                 [ring-basic-auth "0.1.0"]
                 [ring/ring-jetty-adapter "1.0.1"]]
  ;; don't want all that logging going to the repl
  :swank-options {:repl-out-root false})
