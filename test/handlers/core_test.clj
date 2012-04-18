(ns handlers.core-test
  (:use clojure.test
        overtone.libs.handlers))

(comment
  (defn fac
    [v]
    (if (= v 0)
      0
      (+ v (fac (dec v)))))

  (do
    (let [emh {:syncs {::baz #(println "baz")}
               :asyncs {::qux #(println "qux")}
               :sync-one-shots {::fuz {:local-key ::eggs
                                       :handler #(println "eggs")}}}]
      (assert (= 3 (emh-count-handlers emh)))
      (assert (= [::baz ::qux ::fuz] (emh-keys emh))))

    (let [handlers { [:a :b :c] {:syncs {::foo #(println "foo")}
                                 :asyncs {::bar #(println "bar")}}

                     [:d :e :f] {:syncs {::baz #(println "baz")}
                                 :asyncs {::qux #(println "qux")}
                                 :sync-one-shots {:fuz {:local-key ::eggs
                                                        :handler #(println "eggs")}}}
                     [:g :h :i] {}}]

      ;; there should be 5 handlers
      (assert (= 5 (handlers-count-all handlers)))

      ;; removing empty event matchers shouldn't modify the handler count
      (assert (= 5 (handlers-count-all (handlers-rm-empty-event-matchers handlers))))

      ;; after removing empty event matchers, there should be no key [:g :h :i]
      (assert (= nil (get handlers {})))
      (assert (= nil (get (handlers-rm-empty-event-matchers handlers) nil)))

      (assert (= 4 (handlers-count-all (handlers-rm-key handlers ::baz))))
      )

    (let [hp (mk-handler-pool "foo description")]

      (assert (= "foo description"  (:desc hp)))
      (assert (= 0 (count-handlers hp)))
      (assert (= [] (all-keys hp)))

      (add-handler! hp [:a :b :c] ::beans (fn [e] (println "foo")))
      (add-handler! hp [:d :e :f] ::eggs (fn [e] (println "eggs")))
      (assert (= 2 (count-handlers hp)))
      (pprint hp)
      (assert (= (sort [::eggs ::beans]) (sort (all-keys hp))))
      (print "Event matcher keys: " (event-matcher-keys hp [:a :b :c]))
      (assert (= [::beans] (event-matcher-keys hp [:a :b :c])))
      (assert (= [::eggs] (event-matcher-keys hp [:d :e :f])))

      (add-sync-handler! hp [:f :b :b] ::cheese (fn [e] (println "cheese")))
      (assert (= 3 (count-handlers hp)))
      (assert (= [::cheese] (event-matcher-keys hp [:f :b :b])))

      ;; keys should be unique per hp
      (let [p (promise)]
        (add-handler! hp [:f :b :b] ::eggs (fn [e] (deliver p :promise-kept)))
        (assert (= 3 (count-handlers hp)))
        (assert (= (sort [::cheese ::eggs]) (sort (event-matcher-keys hp [:f :b :b]))))
        (event hp [:f :b :b])
        (assert (= :promise-kept (deref p 500 :timeout)))
        )

      (let [p (promise)]
        (add-sync-handler! hp [:f :o :o] ::eggs (fn [e] (deliver p :promise-kept)))
        ;; around 0.2ms on my machine
        (time (do (event hp [:f :o :o]) @p)))

      (let [p (promise)]
        (add-sync-handler! hp [:f :o :o] ::eggs (fn [e] (deliver p :promise-kept)))
        (assert (= 3 (count-handlers hp)))
        (assert (= (sort [::eggs]) (sort (event-matcher-keys hp [:f :o :o]))))
        (event hp [:f :o :o])
        (assert (= :promise-kept (deref p 500 :timeout)))
        )

      (let [p (promise)]
        (add-handler! hp [:f :o :o] ::eggs (fn [e] (deliver p :promise-kept)))
        ;; around 0.2ms on my machine
        (time (do (sync-event hp [:f :o :o]) @p)))


      (add-handler! hp [:b :a :r] ::cheggers (fn [e] (fac 1000)))
      (add-handler! hp [:b :a :g] ::cheggers2 (fn [e] (fac 1000)))
      (time (dotimes [x 1000] (event hp [:b :a :r]) (event hp [:b :a :g])))
      )


    (let [hp (mk-handler-pool "foobar")]
      (add-one-shot-handler! hp [:s :r :h] ::srh (fn [e] nil))
      (assert (= 1 (count-handlers hp)))
      (sync-event hp [:s :r :h])
      (assert (= 0 (count-handlers hp))))


    (let [hp (mk-handler-pool "foobar")]
      (add-one-shot-sync-handler! hp [:s :r :h] ::srh (fn [e] nil))
      (assert (= 1 (count-handlers hp)))
      (sync-event hp [:s :r :h])
      (assert (= 0 (count-handlers hp))))

    (let [hp (mk-handler-pool "foobarrrr")]
      (add-handler! hp [:my :handler] ::yoyoyo (fn [e] nil))
      (add-handler! hp [:my :handler] ::hohoho (fn [e] nil))
      (assert (= 2 (count-handlers hp)))
      (remove-handler! hp ::yoyoyo)
      (assert (= 1 (count-handlers hp)))
      )

    (let [hp (mk-handler-pool "foobarrrr")]
      (add-handler! hp [:my :handler] ::yoyoyo (fn [e] :overtone/remove-handler))
      (add-handler! hp [:my :handler] ::hohoho (fn [e] nil))
      (assert (= 2 (count-handlers hp)))
      (sync-event hp [:my :handler])
      (assert (= 1 (count-handlers hp)))
      )

    )
  ;;sam

  (let [hp (mk-handler-pool "testing remove-specific-handler!")
        hi (fn [e] nil)]
    (add-sync-handler! hp [:y :o] ::foooo hi)
    (assert (= 1 (count-handlers hp)))
    (remove-specific-handler! hp ::foooo (fn [e] :other))
    (assert (= 1 (count-handlers hp)))
    (assert (remove-specific-handler! hp ::foooo hi))
    (assert (= 0 (count-handlers hp)))
    (assert (not (remove-specific-handler! hp ::foooo hi)))
    )


  (let [hp (mk-handler-pool "foobarrrr")]
    (add-handler! hp [:my :handler] ::yoyoyo (fn [e] nil))
    (assert (= 1 (count-handlers hp)))
    (add-one-shot-handler! hp [:s :r :h] ::srh (fn [e] nil))
    (assert (= 2 (count-handlers hp)))
    (sync-event hp [:s :r :h])
    (assert (= 1 (count-handlers hp)))
    )

  (use 'clojure.pprint)

  (def hp (mk-handler-pool))
  (pprint (all-keys hp))
  (def p (promise))
  (count-handlers hp)
  (def die (fn [e] (println  "die!")))
  (def live (fn [e] (println "live")))
  (add-sync-handler! hp [:s :r :h] ::srh-live live)
  (add-one-shot-handler! hp [:s :r :h] ::foo-die die)
  (sync-event hp [:s :r :h])
  (deref p 100 :no)
  (remove-handler! hp ::foo-die)

  (remove-specific-handler! hp ::foo-die die)
  (count-handlers hp)
  (do
    (pprint hp)
    (println "\n\n"))
  )
