(ns fault.service
  (:require [fault.future :as f])
  (:import (clojure.lang ILookup)
           (fault BlockingExecutor ServiceExecutor ResilientAction ResilientFuture ResilientPromise RejectedActionException)
           (fault.circuit CircuitBreaker BreakerConfig BreakerConfig$BreakerConfigBuilder)
           (fault.metrics ActionMetrics)))

(set! *warn-on-reflection* true)

(defn- wrap-action-fn [action-fn]
  (reify ResilientAction
    (run [_] (action-fn))))

(defprotocol Service
  (submit-action [this action-fn timeout-millis])
  (perform-action [this action-fn])
  (shutdown [this]))

(deftype CLJBreaker [^CircuitBreaker breaker]
  ILookup
  (valAt [this key] (.valAt this key nil))
  (valAt [_ key default]
    (case key
      :open? (.isOpen breaker)
      :config (let [^BreakerConfig config (.getBreakerConfig breaker)]
                {:time-period-in-millis (.timePeriodInMillis config)
                 :failure-threshold (.failureThreshold config)
                 :time-to-pause-millis (.timeToPauseMillis config)})
      default))
  Object
  (toString [this]
    (str {:open? (.isOpen breaker)
          :config (let [^BreakerConfig config (.getBreakerConfig breaker)]
                    {:time-period-in-millis (.timePeriodInMillis config)
                     :failure-threshold (.failureThreshold config)
                     :time-to-pause-millis (.timeToPauseMillis config)})})))

(deftype CLJMetrics [^ActionMetrics metrics]
  ILookup
  (valAt [this key] (.valAt this key nil))
  (valAt [_ key default]
    (let [millis (* 1000 (.getSecondsTracked metrics))]
      (case key
        :failures (.getFailuresForTimePeriod metrics millis)
        :errors (.getErrorsForTimePeriod metrics millis)
        :successes (.getSuccessesForTimePeriod metrics millis)
        :time-outs (.getTimeoutsForTimePeriod metrics millis)
        default)))
  Object
  (toString [this]
    (let [millis (* 1000 (.getSecondsTracked metrics))]
      (str {:failures (.getFailuresForTimePeriod metrics millis)
            :errors (.getErrorsForTimePeriod metrics millis)
            :successes (.getSuccessesForTimePeriod metrics millis)
            :time-outs (.getTimeoutsForTimePeriod metrics millis)}))))

(deftype CLJService
  [^ServiceExecutor executor ^CLJMetrics metrics ^CLJBreaker breaker]
  Service
  (submit-action [_ action-fn timeout-millis]
    (try (f/->CLJResilientFuture
           (.promise ^ResilientFuture (.submitAction executor
                                                     (wrap-action-fn action-fn)
                                                     timeout-millis)))
         (catch RejectedActionException e
           (f/rejected-action-future (.reason e)))))
  (perform-action [_ action-fn]
    (try (f/->CLJResilientFuture
           ^ResilientPromise (.performAction executor (wrap-action-fn action-fn)))
         (catch RejectedActionException e
           (f/rejected-action-future (.reason e)))))
  (shutdown [_] (.shutdown executor))
  ILookup
  (valAt [this key] (.valAt this key nil))
  (valAt [_ key default]
    (case key
      :metrics metrics
      :service-executor executor
      :breaker breaker
      default)))

(defn swap-breaker-config!
  [{:keys [circuit-breaker]}
   {:keys [time-period-in-millis failure-threshold time-to-pause-millis]}]
  (.setBreakerConfig
    ^CircuitBreaker circuit-breaker
    ^BreakerConfig (doto (BreakerConfig$BreakerConfigBuilder.)
                     (.timePeriodInMillis time-period-in-millis)
                     (.failureThreshold failure-threshold)
                     (.timeToPauseMillis time-to-pause-millis)
                     (.build))))

(defn close-circuit! [{:keys [circuit-breaker]}]
  (.forceClosed ^CircuitBreaker circuit-breaker))

(defn open-circuit! [{:keys [circuit-breaker]}]
  (.forceOpen ^CircuitBreaker circuit-breaker))

(defn service-executor [pool-size max-concurrency]
  (let [executor (BlockingExecutor. pool-size max-concurrency)]
    (->CLJService executor
                  (->CLJMetrics (.getActionMetrics executor))
                  (->CLJBreaker (.getCircuitBreaker executor)))))
