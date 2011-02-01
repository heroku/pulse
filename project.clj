(defproject pulse "0.0.1"
  :dependencies [
    [org.clojure/clojure "1.3.0-alpha4"]
    [clj-redis "0.0.8"]
    [clj-json "0.3.1"]
    [ring/ring-jetty-async-adapter "0.3.3-SNAPSHOT"]
    [com.espertech/esper "4.1.0" :exclusions [commons-logging log4j]]
    [commons-logging/commons-logging "1.1.1"]])
