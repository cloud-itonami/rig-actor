(ns rig.murakumo
  "JVM I/O edge: submit/poll generation jobs against the REAL murakumo
  distributed generation backend (ADR-2607123000 §3), reusing
  `cloud-murakumo.gen`/`cloud-murakumo.queue-kotoba` verbatim (sibling
  gftdcojp repo, `:local/root` dep — see deps.edn) rather than re-deriving
  the job-normalization / kotoba-queue wire protocol here.

  NOTE on `modality`: murakumo.edn's `:apps :generation :functions` entry for
  this actor's engine is keyed `:autorig` (the app-level name, matching this
  actor's own domain and ADR-2607123000's persona table), but its
  `:fn/modality` value is `:rig` (see
  orgs/gftdcojp/cloud-murakumo/resources/murakumo.edn — `:fn/engine :unirig
  :fn/modality :rig`). `cloud-murakumo.gen/fn-for-modality` resolves by
  `:fn/modality`, so `modality` here MUST be `:rig`, not `:autorig` —
  `:autorig` would never match and `function` would always throw. Verified
  against cloud-murakumo's murakumo.edn during this actor's STEP 5
  lint+test (same category of easy-to-miss mismatch the illust reference's
  rank/*bytes* fixes guard against — see README deviations note).

  HONEST LIMIT: this actor only SUBMITS jobs onto the `gftd-murakumo` kotoba
  graph and POLLS for completion — it does not itself run GPU inference. A
  murakumo fleet worker (`clojure -M:worker --kotoba-url https://kotobase.net
  --kotoba-graph gftd-murakumo`, running on a Mac-mini / `gad` node) has to be
  up and consuming that queue for jobs to ever leave :queued. Same
  operational dependency ai-gftd-apex already has on cloud-murakumo.

  Also HONEST LIMIT (ADR-2607123000 §2, autorig-specific): unlike the other
  six actors, this one does not generate a mesh from nothing — every
  submitted job carries `:refs [input-mesh-cid]` resolved by rig.generate
  from RIG_INPUT_MESH_CID. See rig.generate's docstring."
  (:require [kotoba.net.jvm-host :as jvm-host]
            [cloud-murakumo.spec :as spec]
            [cloud-murakumo.gen :as gen]
            [cloud-murakumo.queue-kotoba :as qk])
  )

(def modality :rig)
(def actor-id "gftd-rig-actor")

(defn function
  "This actor's murakumo.edn `:apps :generation` function entry (SSoT stays
  in cloud-murakumo, we just look it up — never hardcode the model list)."
  []
  (or (gen/fn-for-modality (spec/functions (spec/load-spec)) modality)
      (throw (ex-info "no :rig generation function in murakumo.edn" {:modality modality}))))

(defn- kotoba-url [] (or (System/getenv "MURAKUMO_KOTOBA_URL") "https://kotobase.net"))
(defn- kotoba-db-name [] (or (System/getenv "MURAKUMO_KOTOBA_DB_NAME") "gftd-murakumo"))

(defn conn
  "queue-kotoba connection map. Auth: MURAKUMO_KOTOBA_TOKEN (bearer, the
  simplest operational path) preferred; falls back to a CACAO seed
  (MURAKUMO_KOTOBA_SEED, in-process JVM signing via
  cloud_murakumo.queue-kotoba/fresh-cacao — no `kotoba` CLI needed, see that
  ns's own 2026-07-12 fix). `:graph` is the CID queue-kotoba's own reads
  (`.q`) need explicitly; for writes the server recomputes it from the
  verified CACAO issuer + :db-name and ignores whatever :graph the client
  sends, but reads have no CACAO to derive it from, so this must compute the
  SAME CID client-side (qk/canonical-tenant-graph) whenever a seed is
  available — a bare :db-name string (the pre-fix behavior) is not a valid
  graph CID and would make reads look at the wrong (empty) graph."
  []
  (let [db-name (kotoba-db-name)
        seed (System/getenv "MURAKUMO_KOTOBA_SEED")
        graph (if seed
                (qk/canonical-tenant-graph (qk/did-derive seed) db-name)
                (System/getenv "MURAKUMO_KOTOBA_GRAPH"))]
    (qk/conn (kotoba-url) graph
             (cond-> {:db-name db-name}
               (System/getenv "MURAKUMO_KOTOBA_TOKEN")
               (assoc :token (System/getenv "MURAKUMO_KOTOBA_TOKEN"))
               seed (assoc :seed seed)))))

(defn submit!
  "candidate ({:prompt :params}) -> the enqueued :gen.job map (has
  :gen.job/id). candidate's :params carries :refs [input-mesh-cid] (set by
  rig.generate) — passed through to cloud-murakumo.gen/job's request map so
  the unirig worker knows which mesh to rig; cloud-murakumo itself is NOT
  modified, gen/job already accepts a top-level :refs key. http-fn injected
  (default real HTTP) so callers can fake this offline in tests."
  ([candidate] (submit! (function) (conn) candidate {}))
  ([f c {:keys [prompt params]} {:keys [http-fn]}]
   (let [job (assoc (gen/job f {:prompt prompt :refs (:refs params) :params params} actor-id)
                    :gen.job/id (str (random-uuid)))]
     (qk/enqueue! (cond-> {} http-fn (assoc :http-fn http-fn)) c job {}))))

(defn poll
  "job-id -> the current job map (nil if not found yet — a submit! + poll in
  the same tick can race the kotoba write, callers must tolerate nil)."
  ([job-id] (poll (conn) job-id {}))
  ([c job-id {:keys [http-fn]}]
   (qk/job-by-id (cond-> {} http-fn (assoc :http-fn http-fn)) c job-id)))

(defn done? [job] (= :done (:gen.job/status job)))
(defn failed? [job] (= :failed (:gen.job/status job)))

;; ── artifact fetch ───────────────────────────────────────────────────────

(defn jvm-http-get
  "Plain GET, returns {:status :body-bytes}. Used for both http(s) artifact
  URLs and the kotoba CID gateway fallback below. Delegated to
  kotoba.net.jvm-host (:as-bytes)."
  [url]
  (let [resp ((jvm-host/http-transport {:timeout-seconds 120 :as-bytes true})
              {:url url :method :get})]
    {:status (:status resp) :body-bytes (:body resp)}))

(defn artifact-url
  "One `:gen.job/artifacts` entry -> a fetchable URL. If it's already an
  http(s) URL, use it as-is. Otherwise treat it as a kotoba CID and resolve
  it against a gateway — HONEST LIMIT: this CID->URL convention
  (KOTOBASE_ARTIFACT_BASE_URL, default `<kotoba-url>/ipfs/`) is a best-effort
  guess, not a confirmed contract with murakumo ops; correct it via the env
  var if wrong, no code change needed."
  [artifact]
  (if (re-find #"^https?://" artifact)
    artifact
    (str (or (System/getenv "KOTOBASE_ARTIFACT_BASE_URL") (str (kotoba-url) "/ipfs/"))
         artifact)))

(defn fetch-artifact!
  "First artifact of a :done job -> raw bytes, or nil if none / fetch failed.
  get-fn injected for offline tests."
  ([job] (fetch-artifact! job jvm-http-get))
  ([job get-fn]
   (when-let [a (first (:gen.job/artifacts job))]
     (try
       (let [{:keys [status body-bytes]} (get-fn (artifact-url a))]
         (when (= 200 status) body-bytes))
       (catch Exception _ nil)))))
