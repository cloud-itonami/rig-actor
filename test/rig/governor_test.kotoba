(ns rig.governor-test
  (:require [clojure.test :refer [deftest testing is]]
            [rig.governor :as governor]))

(def clean-asset
  {:kind :asset :format "vrm" :license :cc0 :title "humanoid rig, strict symmetry"
   :artifact-bytes (byte-array [1 2 3]) :safety-flag false})

(deftest clean-asset-passes
  (is (empty? (governor/violations clean-asset)))
  (is (governor/ok? clean-asset)))

(deftest no-artifact-is-hard
  (is (some #(re-find #"no fetched artifact" %)
           (governor/violations (assoc clean-asset :artifact-bytes nil)))))

(deftest format-mismatch-is-hard
  (is (seq (governor/violations (assoc clean-asset :format "fbx")))))

(deftest glb-format-is-allowed
  (testing "unirig can also ingest/emit glb, not just vrm"
    (is (empty? (governor/violations (assoc clean-asset :format "glb"))))))

(deftest proprietary-license-is-hard
  (testing "free-asset charter: :proprietary always refused"
    (is (seq (governor/violations (assoc clean-asset :license :proprietary))))))

(deftest cc-by-is-allowed
  (is (empty? (governor/violations (assoc clean-asset :license :cc-by)))))

(deftest unsafe-flag-is-hard
  (is (seq (governor/violations (assoc clean-asset :safety-flag true)))))

(deftest missing-title-is-hard
  (is (seq (governor/violations (assoc clean-asset :title "")))))

(deftest non-asset-write-kind-is-hard
  (testing "no-actuation: this governor only ever passes :kind :asset writes"
    (is (seq (governor/violations (assoc clean-asset :kind :canon))))))

(deftest multiple-violations-all-reported
  (let [dirty (assoc clean-asset :license :proprietary :safety-flag true :artifact-bytes nil)]
    (is (= 3 (count (governor/violations dirty))))))
