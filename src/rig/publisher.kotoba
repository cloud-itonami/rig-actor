(ns rig.publisher
  "Publisher — the outbound surface for a governor-passed asset, injected so
  the network is a swap (MockPublisher default for tests ‖ real app-aozora
  createRecord via `rig.aozora`). Same shape as
  `cloud_itonami.media.publisher` / `illust.publisher`."
  )

(def collection
  "A DISTINCT collection from every other actor's own stream (net.rig.*
  only, never mixed with net.illust.*, net.sculpt.*, etc. — each actor is
  its own separate, independently-governed identity)."
  "net.rig.asset.publish")

(defprotocol Publisher
  (publish! [p record] "publish one asset record -> {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/rig/" (hash record))
     :cid (str "mock:" (hash record))}))

(defn mock-publisher
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))
