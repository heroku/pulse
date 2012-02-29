(ns drain.adapter
  (:require [cheshire.core :as json])
  (:import (java.net InetSocketAddress)
           (java.util.concurrent Executors)
           (org.jboss.netty.bootstrap ServerBootstrap)
           (org.jboss.netty.channel ChannelPipelineFactory Channels
                                    SimpleChannelUpstreamHandler)
           (org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory)
           (org.jboss.netty.handler.codec.frame DelimiterBasedFrameDecoder
                                                Delimiters)
           (org.jboss.netty.handler.codec.http DefaultHttpResponse
                                               HttpHeaders$Names
                                               HttpHeaders$Values
                                               HttpRequest HttpResponseStatus
                                               HttpServerCodec HttpVersion)
           (org.jboss.netty.util CharsetUtil)))

(defn http-message? [o]
  (instance? HttpRequest (.getMessage o)))

(defn http-ack []
  (doto (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK)
    (.setHeader HttpHeaders$Names/TRANSFER_ENCODING HttpHeaders$Values/CHUNKED)
    (.setChunked true)))

(defonce listeners (atom {}))

(defn listeners-loop [interval]
  (doseq [[listener handler] @listeners]
    (handler listener))
  (Thread/sleep interval)
  (recur interval))

(defn channel-handler [on-message downstream]
  (proxy [SimpleChannelUpstreamHandler] []
    (messageReceived [ctx e]
      (if (http-message? e)
        (if (= "POST" (-> e .getMessage .getMethod str))
          (.write (.getChannel e) (http-ack))
          (swap! listeners assoc (.getChannel e) downstream))
        (let [payload (-> (.getMessage e)
                          (.getContent)
                          (.toString CharsetUtil/UTF_8))]
          (on-message (json/parse-string payload true)))))
    (exceptionCaught [ctx e]
      (.printStackTrace (.getCause e)))))

(defn pipeline-factory [on-message downstream]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (doto (Channels/pipeline)
        (.addLast "codec" (HttpServerCodec.))
        (.addLast "framer" (DelimiterBasedFrameDecoder.
                            8192 false (Delimiters/lineDelimiter)))
        (.addLast "handler" (channel-handler on-message downstream))))))

(defn server [port on-message & [downstream interval]]
  (let [bootstrap (doto (ServerBootstrap.
                         (NioServerSocketChannelFactory.
                          (Executors/newCachedThreadPool)
                          (Executors/newCachedThreadPool)))
                    (.setPipelineFactory (pipeline-factory on-message
                                                           downstream)))
        channel (.bind bootstrap (InetSocketAddress. port))]
    (when (and downstream interval)
      (listeners-loop downstream interval))
    (fn [] (.close channel))))

;; As an example:
(defn -main [& [port]]
  (let [port (Integer/parseInt (or (System/getenv "PORT") "5000"))]
    (server port #(println "Event:" %))
    (println "Started on port" port)))
