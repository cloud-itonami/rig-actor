(ns rig.persona
  "Loads this actor's persona.edn (data, not code — see resources/persona.edn).
  Kept as a thin ns so callers never `slurp` the resource themselves."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn load-persona
  "-> the parsed persona.edn map."
  []
  (edn/read-string (slurp (io/resource "persona.edn"))))
