(ns rig.governor
  "AssetGovernor — the independent layer that earns the round's co-scientist
  winner the right to be committed/published (ADR-2607122200 §4). A pure,
  structural gate (same discipline as `cloud_itonami.media/governor-violations`
  — not a semantic quality judge, rig.cosci already ran that; this re-checks
  hard invariants rig.judge/rig.cosci have no notion of: license freedom,
  format, safety, no-actuation).

  Single invariant: the actor never commits/publishes an asset this governor
  would reject."
  (:require [clojure.string :as str]))

(def allowed-licenses #{:cc0 :cc-by :public-domain})
(def allowed-formats #{"vrm" "glb"})

(defn no-artifact? [{:keys [artifact-bytes]}] (nil? artifact-bytes))
(defn format-mismatch? [{:keys [format]}] (not (contains? allowed-formats format)))
(defn license-not-free? [{:keys [license]}] (not (contains? allowed-licenses license)))
(defn unsafe-flag? [{:keys [safety-flag]}] (boolean safety-flag))
(defn missing-title? [{:keys [title]}] (str/blank? title))
(defn actuation-not-asset? [{:keys [kind]}] (not= :asset kind))

(defn violations
  "asset (the round winner, augmented with :license/:title/:kind by
  rig.loop before this check) -> violation strings. Empty = passes."
  [asset]
  (cond-> []
    (no-artifact? asset)        (conj "asset has no fetched artifact bytes")
    (format-mismatch? asset)    (conj (str "format not in allowed set " allowed-formats
                                           ": " (:format asset)))
    (license-not-free? asset)   (conj (str "license must be one of " allowed-licenses
                                           ", got " (:license asset)))
    (unsafe-flag? asset)        (conj "upstream safety flag set — refused")
    (missing-title? asset)      (conj "asset missing a title")
    (actuation-not-asset? asset) (conj "governor write kind must be :asset (no-actuation)")))

(defn ok? [asset] (empty? (violations asset)))
