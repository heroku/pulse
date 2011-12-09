(ns pulse.io
  (:require [pulse.util :as util]
            [pulse.log :as log]
            [pulse.queue :as queue]
            [pulse.conf :as conf]
            [clj-redis.client :as redis])
  (:import (clojure.lang LineNumberingPushbackReader)
           (org.jboss.netty.util CharsetUtil)
           (org.jboss.netty.buffer ChannelBuffers)
           (java.io InputStreamReader BufferedReader PrintWriter)
           (java.net Socket SocketException ConnectException)
           (org.jboss.netty.bootstrap ServerBootstrap)
           (org.jboss.netty.channel.socket.nio NioServerSocketChannelFactory)
           (java.util.concurrent Executors Executor)
           (java.net InetSocketAddress)
           (org.jboss.netty.channel Channel
                                    Channels
                                    SimpleChannelUpstreamHandler
                                    ChannelHandlerContext
                                    MessageEvent
                                    ChannelFutureListener)
           (org.jboss.netty.handler.codec.http HttpServerCodec
                                               HttpRequest
                                               DefaultHttpResponse
                                               HttpVersion
                                               HttpResponseStatus
                                               HttpHeaders
                                               HttpChunk
                                               DefaultHttpChunk)
           (org.jboss.netty.handler.codec.frame DelimiterBasedFrameDecoder
                                                Delimiters)
           (org.jboss.netty.handler.codec.http HttpHeaders$Names
                                               HttpHeaders$Values)))

(defn log [& data]
  (apply log/log :ns "io" data))

(defn http-message? [o]
  (instance? HttpRequest (.getMessage o)))

(defn http-ack []
  (doto (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK)
    (.setHeader HttpHeaders$Names/CONTENT_TYPE "application/json")
    (.setHeader HttpHeaders$Names/TRANSFER_ENCODING HttpHeaders$Values/CHUNKED)
    (.setChunked true)))

(defn channel-handler [on-message]
  (proxy [SimpleChannelUpstreamHandler] []
    (messageReceived [ctx e]
      (log :fn "message-received" :http-message (http-message? e))
      (if (http-message? e)
        (-> (.getChannel e)
            (.write (http-ack)))
        (let [chunk   (.getMessage e)
              payload (-> chunk
                          (.getContent)
                          (.toString CharsetUtil/UTF_8))]
          (on-message payload))))
    (exceptionCaught [ctx e]
      (.printStackTrace (.getCause e)))))

(defn server [port on-message]
  (let [handler   (channel-handler on-message)
        pipeline  (doto (Channels/pipeline)
                    (.addLast "codec" (HttpServerCodec.))
                    #_(.addLast "line-decoder" (line-decoder))
                    (.addLast "handler" handler))
        bootstrap (doto (ServerBootstrap.
                         (NioServerSocketChannelFactory.
                          (Executors/newCachedThreadPool)
                          (Executors/newCachedThreadPool)))
                    (.setPipeline pipeline))
        channel   (.bind bootstrap (InetSocketAddress. port))]
    (fn [& _] (.close channel))))

(defn init-bleeders [port apply-queue]
  (log :fn "init-bleeders" :at "start" :port port)
  (server port #(queue/offer apply-queue %)))

(defn init-publishers [publish-queue redis-url chan ser workers]
  (let [redis (redis/init {:url redis-url})]
    (log :fn "init-publishers" :at "start" :chan chan)
    (dotimes [i workers]
      (log :fn "init-publishers" :at "spawn" :chan chan :index i)
      (util/spawn-loop (fn []
        (let [data (queue/take publish-queue)
              data-str (try
                         (ser data)
                         (catch Exception e
                           (log :fn "init-publishers" :at "exception" :data (pr-str data))
                           (throw e)))]
          (redis/publish redis chan data-str)))))))

(defn init-subscriber [redis-url chan deser apply-queue]
  (let [redis (redis/init {:url redis-url})]
    (log :fn "init-subscriber" :at "start" :chan chan)
    (redis/subscribe redis [chan]
      (fn [_ data-json]
        (queue/offer apply-queue (deser data-json))))))
