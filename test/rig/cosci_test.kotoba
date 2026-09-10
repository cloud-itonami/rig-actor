(ns rig.cosci-test
  (:require [clojure.test :refer [deftest testing is]]
            [rig.cosci :as cosci]))

(defn- result [id status & {:keys [format bytes safety] :or {format "vrm" bytes (byte-array [1]) safety false}}]
  {:candidate {:candidate/id id :prompt (str "prompt-" id) :gene {:rig-type "humanoid" :symmetry "strict"}}
   :status status :artifact-bytes (if (= status :done) bytes nil)
   :format (when (= status :done) format) :expected-format "vrm" :safety-flag safety})

(deftest reflect-hard-gates
  (testing "not-done fails"
    (is (false? (:pass? (cosci/reflect (result "a" :failed))))))
  (testing "no artifact fails even if status is :done (defensive)"
    (is (false? (:pass? (cosci/reflect (assoc (result "a" :done) :artifact-bytes nil))))))
  (testing "format mismatch fails"
    (is (false? (:pass? (cosci/reflect (result "a" :done :format "fbx"))))))
  (testing "safety flag fails"
    (is (false? (:pass? (cosci/reflect (result "a" :done :safety true))))))
  (testing "clean candidate passes"
    (is (true? (:pass? (cosci/reflect (result "a" :done)))))))

(deftest reflect-all-partitions
  (let [{:keys [passed failed]} (cosci/reflect-all [(result "a" :done) (result "b" :failed)])]
    (is (= 1 (count passed)))
    (is (= 1 (count failed)))))

(deftest rank-orders-by-judge-score
  (let [passed [(result "a" :done) (result "b" :done) (result "c" :done)]
        judged {"a" {:score 30} "b" {:score 90} "c" {:score 60}}
        ranked (cosci/rank passed judged)]
    (is (= ["b" "c" "a"] (mapv #(get-in % [:result :candidate :candidate/id]) ranked)))))

(deftest rank-missing-judge-score-ranks-last-not-crash
  (let [passed [(result "a" :done) (result "b" :done)]
        judged {"a" {:score 50}}   ; "b" has no judge entry
        ranked (cosci/rank passed judged)]
    (is (= 2 (count ranked)))
    (is (= "a" (get-in (first ranked) [:result :candidate :candidate/id])))))

(deftest run-round-empty-when-all-fail-reflection
  (let [{:keys [ranked]} (cosci/run-round [(result "a" :failed) (result "b" :failed)] {} 0)]
    (is (empty? ranked))))

(deftest run-round-evolution-advances-round
  (let [{:keys [evolution]} (cosci/run-round [(result "a" :done)] {"a" {:score 80}} 5)]
    (is (= 6 (:next-round evolution)))
    (is (= {:rig-type "humanoid" :symmetry "strict"} (:elite-gene evolution)))))

(deftest meta-review-reports-winner
  (let [{:keys [meta-review]} (cosci/run-round [(result "a" :done) (result "b" :done)]
                                               {"a" {:score 40} "b" {:score 95}} 0)]
    (is (= "b" (:winner meta-review)))
    (is (= 95 (:winner-score meta-review)))))
