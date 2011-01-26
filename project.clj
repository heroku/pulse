(defproject pulse "0.0.1"
  :dependencies [
    [org.clojure/clojure "1.3.0-alpha4"]
    [commons-logging/commons-logging "1.1.1"]
    [com.espertech/esper "4.1.0" :exclusions [commons-logging log4j]]])
