(ns
  ^{:doc "A simple event system that handles both synchronous and asynchronous events."
     :author "Jeff Rose & Sam Aaron"}
  overtone.libs.handlers
  (:import (java.util.concurrent Executors))
  (:use [clojure.stacktrace]))

(def ^{:dynamic true} *FORCE-SYNC?* false)
(def ^{:dynamic true} *log-fn* nil)

(defn- cpu-count
  "Get the number of CPUs on this machine."
  []
  (.availableProcessors (Runtime/getRuntime)))

(defrecord HandlerPool [pool asyncs syncs desc])

(defn- log-error
  [& msgs]
  (when *log-fn*
    (*log-fn* (apply str msgs))))

(defn mk-handler-pool
  ([] (mk-handler-pool "No description"))
  ([desc] (let [size (+ 2 (cpu-count))
                pool (Executors/newFixedThreadPool size)
                async-handlers (ref {})
                sync-handlers (ref {})]
            (HandlerPool. pool async-handlers sync-handlers desc))))

(defn- count-handlers
  "Count the number of handlers for the specified event-name and
  key. Throws an exception if the number is unexpected (i.e. not 0 or
  1"
  [handler-pool event-name key]
  (let [res (dosync
             (let [s (if (get-in @(:syncs handler-pool) [event-name key]) 1 0)
                   a (if (get-in @(:asyncs handler-pool) [event-name key]) 1 0)]
               (+ s a)))]
    (when (not (or (= 0 res)
                   (= 1 res)))
      (throw (Exception.
              (str "Invalid number of handlers for handler-pool with desc: "
                   (:desc handler-pool)
                   ". Expected 0 or 1 handlers for event " event-name
                   " and key " key ", instead found " res "."))))
    res))

(defn- clear-empty-handler-maps
  "Removes any handler maps for events that don't have any registered
  handlers."
  [handler-pool]
  (dosync
   (doseq [handler-ref* [(:syncs handler-pool) (:asyncs handler-pool)]]
     (let [handler-map @handler-ref*
           new-map     (into {} (remove (fn [[k v]] (= v {})) handler-map))]
       (ref-set handler-ref* new-map)))))

(defn remove-handler
  "Remove an event handler previously registered to handle events of
  type event-name Removes both sync and async handlers with a given
  key for a particular event type. Returns true if removal was
  successful, nil otherwise.

  (defn my-foo-handler [event] (do-stuff (:val event))

  (add-handler :foo my-foo-handler ::bar-key)
  (event :foo :val 200) ; my-foo-handler gets called with:
                        ; {:event-name :foo :val 200}
  (remove-handler :foo ::bar-key)
  (event :foo :val 200) ; my-foo-handler no longer called"
  [handler-pool event-name key]
  (dosync
   (let [handler-cnt (count-handlers handler-pool event-name key)]
     (doseq [handler-ref* [(:syncs handler-pool) (:asyncs handler-pool)]]
       (let [handlers (get @handler-ref* event-name {})]
         (alter handler-ref* assoc event-name (dissoc handlers key))))
     (let [new-handler-cnt (count-handlers handler-pool event-name key)]
       (not= new-handler-cnt handler-cnt)))
   (clear-empty-handler-maps handler-pool)))

(defn- count-all-handlers
  "Count the total number of handlers in the handler-pool"
  [handler-pool]
  (letfn [(cnt [handlers]
            (reduce + (map (fn [[k v]] (count (keys v))) handlers)))]
    (+ (cnt @(:syncs handler-pool)) (cnt @(:asyncs handler-pool)))))

(defn- returning-count-diff
  "Return the difference in handler count or nil if no difference."
  [handler-pool body-fn]
  (dosync
   (let [cnt (count-all-handlers handler-pool)]
     (body-fn)
     (clear-empty-handler-maps handler-pool)
     (let [diff (- cnt (count-all-handlers handler-pool))]
       (if (> diff 0)
         diff
         nil)))))

(defn remove-event-handlers
  "Remove all handlers (both sync and async) for events of type
  event-name. Returns the number of handlers removed, nil if no
  handlers were removed."
  [handler-pool event-name]
  (returning-count-diff handler-pool
                        #(dosync
                           (alter (:asyncs handler-pool) dissoc event-name)
                           (alter (:syncs handler-pool) dissoc event-name))))

(defn remove-all-handlers
  "Remove all handlers for all events. Returns the number of handlers
  removed, nil if no handlers were removed."
  [handler-pool]
    (returning-count-diff handler-pool
                        #(dosync
                           (ref-set (:asyncs handler-pool) {})
                           (ref-set (:syncs handler-pool) {}))))

(defn add-handler*
  [handler-ref* event-name key handler-fn]
  (dosync
   (let [async-handlers (get @handler-ref* event-name {})]
     (alter handler-ref* assoc event-name (assoc async-handlers key handler-fn))
     [event-name key])))

(defn add-handler
  "Asynchronously runs handler whenever events of type event-name are
  fired. This asynchronous behaviour can be overridden if required -
  see sync-event for more information. Events may be triggered with
  the fns event and sync-event.

  Takes an event-name, a handler fn and a key (to refer back to this
  handler in the future). The handler must accept a single event
  argument, which is a map containing the :event-name property and any
  other properties specified when it was fired.

  (add-handler \"/tr\" ::status-check handler)
  (add-handler :note-down ::note-down (fn [event]
                                        (funky-bass (:note event))))

  Handlers can return :overtone/remove-handler to be removed from the
  handler list after execution."
  [handler-pool event-name key handler-fn]
  (dosync
   (remove-handler handler-pool event-name key)
   (add-handler* (:asyncs handler-pool) event-name key handler-fn)))


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

  (add-sync-handler \"/tr\" ::status-check handler)
  (add-sync-handler :midi-note-down
                    ::midi-note-down-hdlr
                    (fn [event]
                      (funky-bass (:note event))))


  Handlers can return :overtone/remove-handler to be removed from the
  handler list after execution."
  [handler-pool event-name key
  handler-fn]
  (dosync
   (remove-handler handler-pool event-name key)
   (add-handler* (:syncs handler-pool) event-name key handler-fn)))

(defn- run-handler
  "Apply the handler to the args - handling exceptions gracefully."
  [handler event-map]
  (try
    (handler event-map)
    (catch Exception e
      (log-error "Handler Exception - with event-map: " event-map "\n"
                 (with-out-str (.printStackTrace e))))))

(defn- handle-event
  "Runs the event handlers for the given event, and removes any
  handler that returns :overtone/remove-handler"
  [handlers* event]
  (let [event-name (:event-name event)
        handlers (get @handlers* event-name {})
        drop-keys (doall (map first
                              (filter (fn [[k handler]]
                                        (= :overtone/remove-handler (run-handler handler event)))
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

  Bind overtone.libs.handlers/*log-fn* to your logging function if
  you wish to have the stacktraces of any exceptions logged.


  (event ::my-event)
  (event ::filter-sweep-done :instrument :phat-bass)"
  [handler-pool event-name & args]
  {:pre [(even? (count args))]}
  (let [event (apply hash-map :event-name event-name args)]
    (synchronously-handle-events (:syncs handler-pool) event)
    (asynchronously-handle-events (:pool handler-pool) (:asyncs handler-pool) event)))

(defn sync-event
  "Runs all event handlers synchronously regardless of whether they
  were declared as async or not. If handlers create new threads which
  generate events, these will revert back to the default behaviour of
  event (i.e. not forced sync). See event.

  Bind overtone.libs.handlers/*log-fn* to your logging function if
  you wish to have the stacktraces of any exceptions logged."
  [handler-pool event-name & args]
  (binding [*FORCE-SYNC?* true]
    (apply event handler-pool event-name args)))

(defn- event-keys-map
  [handlers]
  (into {} (map (fn [[k v]] [k (keys v)]) handlers)))

(defn all-sync-handler-keys
  "Returns a map of all events names to a seq of associated sync
  handler keys for handlers registered with add-sync-handler."
  [handler-pool]
  (event-keys-map @(:syncs handler-pool)))

(defn all-async-handler-keys
  "Returns a map of all events names to a seq of associated async
  handler keys for handlers registered with add-handler."
  [handler-pool]
  (event-keys-map @(:asyncs handler-pool)))

(defn all-handler-keys
  "Returns a map of all handlers (both sync and async) for all event
  names to seqs of associated handler keys."
  [handler-pool]
  (dosync
   (let [syncs (event-keys-map @(:syncs handler-pool))
         asyncs (event-keys-map @(:asyncs handler-pool))]
     (merge syncs asyncs))))

(defn event-sync-handler-keys
  "Returns a seq of all sync handler keys for the specified event that
  have been registered with add-sync-handler."
  [handler-pool
  event-name]
  (get (all-sync-handler-keys handler-pool) event-name))

(defn event-async-handler-keys
  "Returns a seq of all async handler keys for the specified event
  that have been registered with add-handler."
  [handler-pool event-name]
  (get (all-async-handler-keys handler-pool) event-name))

(defn event-handler-keys
  "Returns a seq of all handler keys (both sync and async) for the
  specified event that have been registered with both add-sync-handler
  and add-handler."
  [handler-pool event-name]
  (get (all-handler-keys handler-pool) event-name))
