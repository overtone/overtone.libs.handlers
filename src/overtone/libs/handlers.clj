(ns
  ^{:doc "A simple event system that processes fired events in a thread pool."
     :author "Jeff Rose & Sam Aaron"}
  overtone.libs.handlers
  (:import (java.util.concurrent Executors))
  (:use [clojure.stacktrace]))

(def ^{:dynamic true} *FORCE-SYNC?* false)
(def ^{:dynamic true} *log-debug* nil)

(defn- cpu-count
  "Get the number of CPUs on this machine."
  []
  (.availableProcessors (Runtime/getRuntime)))

(defrecord HandlerPool [pool asyncs syncs desc])

(defn- debug
  [& msgs]
  (when *log-debug*
    (*log-debug* (apply str msgs))))

(defn mk-handler-pool
  ([] (mk-handler-pool "No description"))
  ([desc] (let [size (+ 2 (cpu-count))
                pool (Executors/newFixedThreadPool size)
                async-handlers (ref {})
                sync-handlers (ref {})]
            (HandlerPool. pool async-handlers sync-handlers desc))))

(defn on-event*
  [handler-ref* event-name key handler-fn]
  (debug "Adding event handler: " event-name ", " key)
  (dosync
   (let [async-handlers (get @handler-ref* event-name {})]
     (alter handler-ref* assoc event-name (assoc async-handlers key handler-fn))
     [event-name ])))

(defn add-handler
  "Asynchronously runs handler whenever events of type event-name are
  fired. This asynchronous behaviour can be overridden if required -
  see sync-event for more information. Events may be triggered with
  the fns event and sync-event.

  Takes an event-name, a handler fn and a key (to refer back to this
  handler in the future). The handler must accept a single event
  argument, which is a map containing the :event-name property and any
  other properties specified when it was fired.

  (on-event \"/tr\" ::status-check handler)
  (on-event :midi-note-down ::note-down (fn [event]
                                          (funky-bass (:note event))))

  Handlers can return :overtone/remove-handler to be removed from the
  handler list after execution."
  [handler-pool event-name key handler-fn]
  (on-event* (:asyncs handler-pool) event-name key handler-fn))


(defn add-sync-handler
  "Synchronously runs handler whenever events of type event-name are
  fired on the event handling thread i.e. causes the event handling
  thread to block until all sync events have been handled. Events may
  be triggered with the fns event and sync-event.

  Takes an event-name(name of the event), a handler fn and a key (to
  refer back to this handler in the future). The handler can
  optionally accept a single event argument, which is a map containing
  the :event-name property and any other properties specified when it
  was fired.

  (on-event \"/tr\" ::status-check handler)
  (on-event :midi-note-down
            ::midi-note-down-hdlr
            (fn [event]
              (funky-bass (:note event))))


  Handlers can return :overtone/done to be removed from the handler
  list after execution."
  [handler-pool event-name key handler-fn]
  (on-event* (:syncs handler-pool) event-name key handler-fn))

(defn remove-handler
  "Remove an event handler previously registered to handle events of
  type event-name Removes both sync and async handlers with a given
  key for a particular event type.

  (defn my-foo-handler [event] (do-stuff (:val event))

  (on-event :foo my-foo-handler ::bar-key)
  (event :foo :val 200) ; my-foo-handler gets called with:
                        ; {:event-name :foo :val 200}
  (remove-handler :foo ::bar-key)
  (event :foo :val 200) ; my-foo-handler no longer called"
  [handler-pool event-name key]
  (dosync
    (doseq [handler-ref* [(:syncs handler-pool) (:asyncs handler-pool)]]
      (let [handlers (get @handler-ref* event-name {})]
        (alter handler-ref* assoc event-name (dissoc handlers key))))))

(defn remove-event-handlers
  "Remove all handlers (both sync and async) for events of type event-name."
  [handler-pool event-name]
  (dosync
    (alter (:asyncs handler-pool) dissoc event-name)
    (alter (:syncs handler-pool) dissoc event-name))
  nil)

(defn remove-all-handlers
  "Remove all handlers for all events"
  [handler-pool]
  (dosync
   (ref-set (:asyncs handler-pool) {})
   (ref-set (:syncs handler-pool) {})))

(defn- run-handler
  "Apply the handler to the args - handling exceptions gracefully."
  [handler event-map]
  (try
    (handler event-map)
    (catch Exception e
      (debug "Handler Exception - with event-map: " event-map "\n"
             (with-out-str (.printStackTrace e))))))

(defn- handle-event
  "Runs the event handlers for the given event, and removes any handler that
  returns :done."
  [handlers* event]
  (debug "handling event: " event)
  (let [event-name (:event-name event)
        handlers (get @handlers* event-name {})
        drop-keys (doall (map first
                              (filter (fn [[k handler]]
                                        (= :overtone/done (run-handler handler event)))
                                      handlers)))]
    (dosync
      (alter handlers* assoc event-name
             (apply dissoc (get @handlers* event-name) drop-keys)))))

(defn- synchronously-handle-events
  [handlers* event]
  (handle-event handlers* event))

(defn- asynchronously-handle-events
  [thread-pool handlers* event]
  (if *FORCE-SYNC?*
    (handle-event handlers* event)
    (.execute thread-pool #(handle-event handlers* event))))

(defn event
  "Fire an event of type event-name with any number of additional
  properties.

  NOTE: an event requires key/value pairs, and everything gets wrapped
  into an event map.  It will not work if you just pass values.

  (event ::my-event)
  (event ::filter-sweep-done :instrument :phat-bass)"
  [handler-pool event-name & args]
  {:pre [(even? (count args))]}
  (debug "event: " event-name args)
  (let [event (apply hash-map :event-name event-name args)]
    (synchronously-handle-events (:syncs handler-pool) event)
    (asynchronously-handle-events (:pool handler-pool) (:asyncs handler-pool) event)))

(defn sync-event
  "Runs all event handlers synchronously regardless of whether they were
  declared as async or not. If handlers create new threads which generate
  events, these will revert back to the default behaviour of event (i.e. not
  forced sync). See event."
  [& args]
  (binding [*FORCE-SYNC?* true]
    (apply event args)))
