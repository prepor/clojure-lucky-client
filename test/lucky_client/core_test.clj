(ns lucky-client.core-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            [lucky-client.core :refer :all]
            [lucky-client.reactor :as reactor]
            [lucky-client.backend :as backend]
            [lucky-client.client :as client]
            [lucky-client.zmq :as zmq]))

(def ^:dynamic *reactor*)

(defn with-reactor
  [f]
  (let [zmq (zmq/create)
        [stopper reactor] (reactor/create zmq)]
    (try
      (binding [*reactor* reactor]
        (f))
      (finally
        (stopper)
        (.term zmq)))))

(use-fixtures :each with-reactor)

(defmacro with-timeout
  [ch]
  `(async/alt!!
     (async/timeout 1000) (throw (Exception. "Timeout"))
     ~ch ([v#] v#)))

(deftest basic-test
  (let [[backend-stopper requests] (backend/create *reactor* ["tcp://0.0.0.0:6001"])
        client (client/create *reactor* ["tcp://0.0.0.0:6000"])
        backend (async/go-loop []
                  (when-let [[answer method body] (async/<! requests)]
                    (is (= "ping" method))
                    (async/>! answer body)
                    (recur)))]
    (try
      (Thread/sleep 500)
      (let [res (client/request client "ping" "Hey, whatzuuup?")]
        (is (= "Hey, whatzuuup?" (String. (with-timeout res)))))
      (let [res (client/request client "ping" "WHAT?")]
        (is (= "WHAT?" (String. (with-timeout res)))))
      (finally
        (async/close! client)
        (backend-stopper)
        (with-timeout backend)))))

(defn start-backend
  []
  (let [zmq (zmq/create)
        [reactor-stopper reactor] (reactor/create zmq)
        [backend-stopper requests] (backend/create reactor ["tcp://0.0.0.0:6001"])
        worker (async/go-loop []
                 (when-let [[answer body] (async/<! requests)]
                   (async/>! answer body)
                   (recur)))]
    (fn []
      (backend-stopper)
      (try
        (with-timeout worker)
        (finally
          (reactor-stopper)
          (.term zmq))))))
