(ns rig.generate-test
  "Not .cljc (unlike illust.generate-test) — rig.generate itself is plain
  .clj, since it needs JVM System/getenv to resolve RIG_INPUT_MESH_CID. Every
  test here with-redefs rig.generate/input-mesh-cid instead of mutating
  process env (env vars aren't safely mutable per-test / per-thread from
  within the JVM)."
  (:require [clojure.test :refer [deftest testing is]]
            [rig.generate :as generate]))

(def persona {:tags ["network-isekai"]})

(def mesh-cid "bafyrig-mesh-1")

(deftest round-candidates-is-pure-and-reproducible
  (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
    (is (= (generate/round-candidates persona 3 3) (generate/round-candidates persona 3 3)))))

(deftest round-candidates-count-matches-k
  (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
    (is (= 5 (count (generate/round-candidates persona 0 5))))))

(deftest round-candidates-ids-are-unique-within-a-round
  (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
    (let [cs (generate/round-candidates persona 2 4)]
      (is (= 4 (count (distinct (map :candidate/id cs))))))))

(deftest round-candidates-prompt-includes-persona-tags
  (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
    (doseq [c (generate/round-candidates persona 0 3)]
      (is (re-find #"network-isekai" (:prompt c))))))

(deftest different-rounds-vary-the-gene-selection
  (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
    (let [r0 (map :gene (generate/round-candidates persona 0 3))
          r1 (map :gene (generate/round-candidates persona 1 3))]
      (is (not= r0 r1)))))

(deftest candidates-carry-the-resolved-mesh-cid-as-refs
  (testing "every candidate's :params :refs points at the ONE resolved input mesh"
    (with-redefs [generate/input-mesh-cid (constantly mesh-cid)]
      (doseq [c (generate/round-candidates persona 0 3)]
        (is (= [mesh-cid] (get-in c [:params :refs])))))))

(deftest no-input-mesh-cid-yields-zero-candidates
  (testing "HONEST LIMIT: this actor cannot rig a mesh from nothing — no
           RIG_INPUT_MESH_CID means no candidates, not a broken job"
    (with-redefs [generate/input-mesh-cid (constantly nil)]
      (is (= [] (generate/round-candidates persona 0 5))))))
