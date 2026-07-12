(ns rig.ledger
  "The append-only genealogy ledger — plain EDN lines in a git-tracked
  (text2git — never annexed) file. This repo's own git history plus this
  file together ARE the immutable audit trail (ADR-2607122400 §3); no
  Datomic/langchain.db indirection needed for a single-writer file-per-actor
  ledger."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.time Instant]))

(def default-path "ledger/events.edn")

(defn append!
  ([fact] (append! default-path fact))
  ([path fact]
   (io/make-parents path)
   (let [line (pr-str (assoc fact :ledger/at (str (Instant/now))))]
     (with-open [w (io/writer path :append true)]
       (.write w line)
       (.write w "\n")))
   fact))

(defn read-all
  ([] (read-all default-path))
  ([path]
   (let [f (io/file path)]
     (if (.exists f)
       (with-open [r (io/reader f)]
         (mapv edn/read-string (doall (line-seq r))))
       []))))

(defn ledger-line [{:keys [ledger/at t round candidate-id score reason]}]
  (str at " · " (name (or t :unknown))
       (when round (str " round=" round))
       (when candidate-id (str " candidate=" candidate-id))
       (when score (str " score=" score))
       (when reason (str " reason=" (pr-str reason)))))
