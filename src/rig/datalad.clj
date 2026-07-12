(ns rig.datalad
  "JVM I/O edge: persist an ACCEPTED (governor-passed) asset into assets/ —
  raw bytes (annexed to B2 by this repo's own .gitattributes text2git rule)
  + an isekai.asset-shaped EDN manifest sidecar (plain git text) — then
  `datalad save` + `datalad push --to b2` (ADR-2607123000 §5).

  Rejected/losing candidates are NEVER written here — only the ledger
  records them (rig.ledger) — so B2 isn't paid-for-and-polluted with
  disqualified generations."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(def ^:dynamic *assets-dir*
  "Rebindable/redef-able so tests can point writes at a tmp dir instead of
  this repo's real assets/ (write-asset! does real file I/O — only
  save+push!'s shell-out is fake-able via sh-fn)."
  "assets")

(defn asset-path [id format] (str *assets-dir* "/" id "." format))
(defn manifest-path [id] (str *assets-dir* "/" id ".edn"))

(defn ->isekai-manifest
  "asset (governor-passed, augmented) -> the network-isekai `isekai.asset`
  manifest shape (ADR-2607123000 §5 — kept import-ready, actual import into
  network-isekai's Asset Hub is explicit follow-up, not wired here)."
  [{:keys [id kind format title license tags gen-job-id prompt created]}]
  {:asset/id id
   :asset/kind kind
   :asset/format format
   :asset/title title
   :asset/author "gftd-rig-actor"
   :asset/license license
   :asset/tags (vec tags)
   :asset/source :gen
   :asset/gen {:stage :autorig :job-key gen-job-id :prompt prompt
               :provenance "murakumo/unirig via gftd-rig-actor co-scientist loop"}
   :asset/created created})

(defn write-asset!
  "{:id :format :artifact-bytes :manifest} -> {:asset-path :manifest-path}.
  Pure I/O, no git/datalad side effects (see save+push! below) so tests can
  call this against a tmp dir without touching the real dataset."
  [{:keys [id format artifact-bytes manifest]}]
  (let [ap (asset-path id format) mp (manifest-path id)]
    (io/make-parents ap)
    (with-open [o (io/output-stream ap)] (.write o ^bytes artifact-bytes))
    (spit mp (pr-str manifest))
    {:asset-path ap :manifest-path mp}))

(defn save+push!
  "`datalad save` then `datalad push --to <remote>`. sh-fn injected (default
  clojure.java.shell/sh) so tests fake this without touching the real
  dataset/network. Callers must run this from the dataset root (this actor's
  own repo root, since the actor repo IS its DataLad dataset)."
  ([paths msg] (save+push! paths msg "b2" shell/sh))
  ([paths msg remote sh-fn]
   (let [save (apply sh-fn "datalad" "save" "-m" msg paths)]
     (when-not (zero? (:exit save))
       (throw (ex-info "datalad save failed" save)))
     (let [push (sh-fn "datalad" "push" "--to" remote)]
       (when-not (zero? (:exit push))
         (throw (ex-info "datalad push failed" push)))
       {:save save :push push}))))
