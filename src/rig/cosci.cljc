(ns rig.cosci
  "Google co-scientist / AlphaEvolve-shaped tournament over one round of
  auto-rig candidates (ADR-2607122200 §3), ported-in-shape from
  `cloud_murakumo.cosci` (LLM-memory-strategy tournament) but with this
  actor's own candidate data. Same discipline: Reflection is a HARD pass/fail
  gate, not a soft score — a candidate that mostly-worked isn't a
  slightly-worse asset, it's a disqualified one.

    Generation  -> rig.generate/round-candidates (closed gene pool of
                   rig-type/symmetry/bone-density presets over ONE input mesh)
    Reflection  -> hard gates: job actually :done, artifact fetched,
                   declared format matches, no upstream safety flag
    Ranking     -> Elo on rig.judge scores (pairwise, draw-tolerant)
    Proximity   -> cluster near-ties
    Evolution   -> next round number + elite gene bias
    Meta-review -> supervisor summary for the ledger")

;; ── Reflection (hard gates) ───────────────────────────────────────────────

(defn reflect
  "One fetched result -> {:pass? :reason?}. `result` shape:
  {:candidate {...} :status :done|:failed :artifact-bytes bytes-or-nil
  :format \"vrm\" :expected-format \"vrm\" :safety-flag bool}."
  [{:keys [status artifact-bytes format expected-format safety-flag]}]
  (cond
    (not= status :done)            {:pass? false :reason :not-done}
    (nil? artifact-bytes)          {:pass? false :reason :no-artifact}
    (not= format expected-format)  {:pass? false :reason :format-mismatch}
    safety-flag                    {:pass? false :reason :unsafe-flag}
    :else                          {:pass? true}))

(defn reflect-all
  "results -> {:passed [...] :failed [{:result :reason} ...]}."
  [results]
  (let [checked (mapv (fn [r] (assoc (reflect r) :result r)) results)]
    {:passed (mapv :result (filter :pass? checked))
     :failed (vec (remove :pass? checked))}))

;; ── Ranking (Elo on judge score) ──────────────────────────────────────────

(defn- pow [base exp]
  #?(:clj (Math/pow (double base) (double exp)) :cljs (js/Math.pow base exp)))

(defn elo-update [ra rb score-a & {:keys [k] :or {k 24}}]
  (let [expected-a (/ 1.0 (+ 1.0 (pow 10 (/ (- rb ra) 400.0))))]
    [(+ ra (* k (- score-a expected-a)))
     (+ rb (* k (- (- 1.0 score-a) (- 1.0 expected-a))))]))

(defn rank
  "passed results + judged (map candidate-id -> {:score :critique}, may be
  missing entries when rig.judge/score! failed) -> ranked vector, highest
  score first. A candidate with no judge score ranks last, never crashes the
  round."
  ([passed judged] (rank passed judged {}))
  ([passed judged {:keys [draw-tol] :or {draw-tol 2}}]
   (let [scored (mapv (fn [r]
                        (let [id (get-in r [:candidate :candidate/id])]
                          {:result r :score (get-in judged [id :score] 0)
                           :critique (get-in judged [id :critique])}))
                      passed)
         n (count scored)
         ratings (atom (vec (repeat n 1000.0)))]
     (doseq [i (range n) j (range (inc i) n)]
       (let [si (:score (scored i)) sj (:score (scored j))
             outcome (cond (> si (+ sj draw-tol)) 1.0
                           (< si (- sj draw-tol)) 0.0
                           :else 0.5)
             [ri rj] (elo-update (@ratings i) (@ratings j) outcome)]
         (swap! ratings assoc i ri j rj)))
     (->> (map #(assoc %1 :elo %2) scored @ratings)
          (sort-by (juxt :elo :score) #(compare %2 %1))
          vec))))

;; ── Proximity ─────────────────────────────────────────────────────────────

(defn cluster-by-proximity
  ([ranked] (cluster-by-proximity ranked 3))
  ([ranked tol]
   (reduce (fn [clusters {:keys [score] :as r}]
             (if-let [cur (peek clusters)]
               (if (<= (abs (- score (:score (first cur)))) tol)
                 (conj (pop clusters) (conj cur r))
                 (conj clusters [r]))
               [[r]]))
           [] ranked)))

;; ── Evolution ─────────────────────────────────────────────────────────────

(defn evolve-round
  "ranked -> {:next-round n+1 :elite-gene {...}} for rig.generate's next
  round-candidates call (bias arg)."
  [ranked round]
  {:next-round (inc round)
   :elite-gene (get-in (first ranked) [:result :candidate :gene])})

;; ── Meta-review ───────────────────────────────────────────────────────────

(defn meta-review
  [{:keys [round passed-n failed-n ranked clusters]}]
  (let [top (first ranked)]
    {:round round
     :passed passed-n
     :failed failed-n
     :winner (get-in top [:result :candidate :candidate/id])
     :winner-prompt (get-in top [:result :candidate :prompt])
     :winner-score (:score top)
     :winner-elo (:elo top)
     :top-cluster-size (count (first clusters))
     :leaderboard (mapv (fn [r] {:id (get-in r [:result :candidate :candidate/id])
                                 :score (:score r) :elo (:elo r)})
                        (take 5 ranked))}))

;; ── Supervisor: run-round ─────────────────────────────────────────────────

(defn run-round
  "fetched results (one per candidate submitted this round) + judged scores
  -> {:passed :failed :ranked :clusters :evolution :meta-review}. Empty
  :ranked (all candidates failed reflection) is a valid, expected outcome —
  the loop holds the whole round rather than committing a disqualified
  candidate."
  [results judged round]
  (let [{:keys [passed failed]} (reflect-all results)
        ranked (rank passed judged)
        clusters (cluster-by-proximity ranked)]
    {:passed passed :failed failed :ranked ranked :clusters clusters
     :evolution (evolve-round ranked round)
     :meta-review (meta-review {:round round :passed-n (count passed)
                                :failed-n (count failed) :ranked ranked
                                :clusters clusters})}))
