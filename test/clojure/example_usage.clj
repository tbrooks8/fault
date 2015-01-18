(ns clojure.example-usage
  (:require [clojure.core.async :as async :refer [<! >! <!! >!! go]]
            [clj-http.client :as http]
            [fault.async :as fa]
            [fault.core :as fault]
            [fault.service :as service]))

(def api-route (str "http://www.broadbandmap.gov/broadbandmap/"
                    "census/county/%s?format=json")
  )

(defonce county-service (fault/service 10 30))

(defn lookup-state-action [county]
  (fn [] (-> (http/get (format api-route county) {:as :json})
             :body
             :Results
             :county)))

(defn handle-success [success-channel]
  (go (loop []
        (let [success-future (<! success-channel)]
          (println "Success")
          (println (:result success-future))
          (recur)))))

(defn handle-error [err-channel]
  (go (loop []
        (let [error-future (<! err-channel)]
          (println "Error")
          (println (:error error-future))
          (println (:status error-future))
          (recur)))))

(defn thing [in-channel out-channel err-channel]
  (go
    (loop []
      (let [county (<! in-channel)
            f (service/submit-action county-service
                                     (lookup-state-action county)
                                     (fa/return-channels {:success out-channel
                                                          :failed err-channel})
                                     (+ 5000 (rand-int 200)))]
        (when (:rejected? f)
          (println (:rejected-reason f)))
        (recur)))))

(defn run []
  (let [in-channel (async/chan 10)
        out-channel (async/chan 10)
        err-channel (async/chan 10)]
    (thing in-channel out-channel err-channel)
    (handle-success out-channel)
    (handle-error err-channel)
    in-channel))