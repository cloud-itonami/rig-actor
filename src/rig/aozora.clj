(ns rig.aozora
  "Real app-aozora Publisher for gftd-rig-actor — creates a record in the
  net.rig.asset.publish collection on an aozora PDS via
  com.atproto.repo.createRecord, authenticated by a depth-1 self-minted
  CACAO. 1:1 port of the PROVEN `cloud_itonami.media.aozora` (itself ported
  from kawaraban.aozora <- tashikame.aozora, and of gftd-illust-actor's own
  `illust.aozora`) — see that namespace's docstring for the full design
  rationale."
  (:require [clojure.string :as str]
            [rig.cacao :as cacao]
            [rig.publisher :as publisher])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant]
           [java.util UUID]))

(def default-pds "https://pds.aozora.app")

(defn jvm-http-fn [{:keys [url method headers body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (doseq [[k v] headers] (.header b k v))
    (let [req  (-> b (.method (str/upper-case (name (or method :post)))
                             (if body
                               (HttpRequest$BodyPublishers/ofString body)
                               (HttpRequest$BodyPublishers/noBody)))
                   (.build))
          resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn mint-session!
  [{:keys [pds identity json-write json-read http-fn] :or {pds default-pds http-fn jvm-http-fn}}]
  (let [now   (str (Instant/now))
        graph (cacao/canonical-graph (:did identity) cacao/default-db-name)
        cacao (cacao/mint identity
                          {:cap :cap/transact :scope graph}
                          {:aud pds :nonce (str (UUID/randomUUID))
                           :issued-at now
                           :expiry (str (.plusSeconds (Instant/now) 3600))})
        sess  (http-fn {:url     (str pds "/xrpc/com.atproto.server.createSession")
                        :method  :post
                        :headers {"Content-Type" "application/json"}
                        :body    (json-write {:cacao cacao})})
        sbody (json-read (:body sess))
        jwt   (get sbody "accessJwt")]
    (when-not (and (= 200 (:status sess)) jwt)
      (throw (ex-info "aozora createSession failed" {:status (:status sess) :body (:body sess)})))
    jwt))

(defn create-record!
  [{:keys [pds identity json-write json-read http-fn] :or {pds default-pds http-fn jvm-http-fn}}
   jwt record]
  (let [now   (str (Instant/now))
        coll  (or (:collection record) publisher/collection)
        rec   (-> (dissoc record :rkey :collection)
                  (assoc :createdAt now :actor (:did identity)))
        resp  (http-fn {:url     (str pds "/xrpc/com.atproto.repo.createRecord")
                        :method  :post
                        :headers {"Content-Type" "application/json"
                                  "Authorization" (str "Bearer " jwt)}
                        :body    (json-write {:repo       (:did identity)
                                              :collection coll
                                              :rkey       (or (:rkey record) "self")
                                              :record     rec})})
        rbody (json-read (:body resp))]
    (when-not (= 200 (:status resp))
      (throw (ex-info "aozora createRecord failed" {:status (:status resp) :body (:body resp)})))
    {:uri (get rbody "uri") :cid (get rbody "cid")}))

(defn set-profile!
  [opts display-name description]
  (let [jwt (mint-session! opts)]
    (create-record! opts jwt
                    {:collection "app.bsky.actor.profile"
                     :rkey "self"
                     :$type "app.bsky.actor.profile"
                     :displayName display-name
                     :description description})))

(defn aozora-publisher
  [{:keys [identity json-write json-read] :as opts}]
  (assert (:did identity) ":identity with :did is required (cacao/load-or-create-identity!)")
  (assert json-write ":json-write fn is required")
  (assert json-read  ":json-read fn is required")
  (reify publisher/Publisher
    (publish! [_ record]
      (let [jwt (mint-session! opts)]
        (create-record! opts jwt record)))))
