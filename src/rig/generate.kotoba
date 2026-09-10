(ns rig.generate
  "Pure-ish candidate builder for one co-scientist round (ADR-2607123000 §2/§3),
  with ONE deliberate deviation from the illust reference this actor ports:
  auto-rigging is not text-to-X. UniRig (`:fn/engine :unirig`, murakumo.edn's
  `:autorig` app) takes an EXISTING glb/vrm mesh as input (`:refs [cid]`), it
  does not conjure a mesh from a text prompt. There is no upstream pipeline
  yet that automatically hands this actor a mesh from sculpt-actor's own
  output — that cross-actor wiring is explicit follow-up, not built here (see
  README HONEST LIMITS).

  So the 'gene pool' this round-robins across is the RIGGING PRESET/CONFIG
  applied to that one input mesh (rig-type/symmetry/bone-density) instead of
  subject/style/lighting text, and every candidate's :params carries a :refs
  vector pointing at the single input mesh CID resolved (at generate time,
  once per round-candidates call) from RIG_INPUT_MESH_CID. Same 'closed
  hypothesis pool, no LLM in Generation' discipline cloud_murakumo.cosci uses:
  round-candidates is a pure function of (persona, round, k, bias, the
  resolved mesh CID) — re-running the same round number with the same env
  reproduces the same candidates; exploration across the pool happens by
  round number advancing (rig.loop) and by biasing one gene slot toward the
  previous round's elite (rig.cosci/evolve-round).

  HONEST LIMIT: if RIG_INPUT_MESH_CID is unset, this actor has nothing to rig
  — round-candidates returns an EMPTY vector (0 candidates) rather than
  submitting a broken unirig job with no :refs. rig.loop's existing
  budget/pending logic already tolerates a round with 0 submitted candidates
  (nothing gets submitted that tick, next tick tries again), so no special
  casing is needed in rig.loop for this."
  (:require [kotoba.lang.text :as str]))

(defn input-mesh-cid
  "The single existing mesh CID (e.g. a CID from sculpt-actor's own
  accepted output) this round's candidates will rig. Wrapped as a fn (not
  inlined into round-candidates) so tests can with-redefs it instead of
  mutating process env — see test/rig/generate_test.clj."
  []
  (System/getenv "RIG_INPUT_MESH_CID"))

(def gene-pool
  {:rig-type ["humanoid" "quadruped" "prop-armature"]
   :symmetry ["strict" "relaxed"]
   :bone-density ["minimal" "standard" "detailed"]})

(defn- pick [xs seed n] (nth xs (mod (+ seed n) (count xs))))

(defn- gene-for
  "One candidate's gene map. `bias` (from rig.cosci/evolve-round's elite, or
  nil on round 0) pins ONE randomly-chosen slot to the prior winner's value
  instead of round-robining it — elitism without literal crossover machinery,
  honest about being a small closed pool rather than a genuine genetic
  search."
  [round i bias]
  (let [raw {:rig-type     (pick (:rig-type gene-pool) round i)
             :symmetry     (pick (:symmetry gene-pool) round (+ i 1))
             :bone-density (pick (:bone-density gene-pool) round (+ i 2))}]
    (if (and bias (pos? round) (zero? (mod (+ round i) 3)))
      (merge raw (select-keys bias [(nth [:rig-type :symmetry :bone-density] (mod round 3))]))
      raw)))

(defn round-candidates
  "persona + round n (0-based) + k candidates + optional elite bias
  -> [{:candidate/id :prompt :gene :params} ...], where every :params
  includes :refs [input-mesh-cid]. Returns [] (0 candidates) if
  RIG_INPUT_MESH_CID is unset — this actor cannot generate a rig from
  nothing."
  ([persona n k] (round-candidates persona n k nil))
  ([{:keys [tags]} n k bias]
   (if-let [mesh-cid (input-mesh-cid)]
     (vec
      (for [i (range k)]
        (let [{:keys [rig-type symmetry bone-density]} (gene-for n i bias)]
          {:candidate/id (str "r" n "-c" i)
           :prompt (str/join ", " (concat [(str "rig-type:" rig-type)
                                            (str "symmetry:" symmetry)
                                            (str "bone-density:" bone-density)]
                                           tags))
           :gene {:rig-type rig-type :symmetry symmetry :bone-density bone-density}
           :params {:rig-type rig-type :symmetry symmetry :bone-density bone-density
                    :refs [mesh-cid]}})))
     [])))
