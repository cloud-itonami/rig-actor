(ns rig.loop-test
  "Full tick! cycle, fully offline: rig.murakumo/rig.judge/datalad shell
  calls are all faked via with-redefs, and lease/state/ledger paths are
  redirected into a temp dir so the test never touches this repo's own
  .rig/ledger/assets state. rig.generate/input-mesh-cid is also with-redefs'd
  (real round-candidates needs RIG_INPUT_MESH_CID resolved — see
  rig.generate's HONEST LIMIT docstring) so this stays independent of
  whatever the test process's actual env happens to have set."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.shell :as shell]
            [rig.loop :as loop]
            [rig.murakumo :as murakumo]
            [rig.judge :as judge]
            [rig.generate :as generate]
            [rig.ledger :as ledger]
            [rig.datalad :as datalad]
            [rig.publisher :as publisher]))

(defn- tmp-dir []
  (let [f (java.io.File/createTempFile "rig-loop-test" "")]
    (.delete f) (.mkdirs f) f))

(defn- with-tmp-paths [tmp f]
  (binding []
    (with-redefs [loop/lease-path (str tmp "/lease")
                  loop/state-path (str tmp "/state.edn")
                  ledger/default-path (str tmp "/ledger.edn")
                  datalad/*assets-dir* (str tmp "/assets")]
      (f))))

(def fake-jobs (atom {}))

(defn- fake-submit! [candidate]
  (let [id (str "job-" (:candidate/id candidate))]
    (swap! fake-jobs assoc id {:gen.job/id id :gen.job/status :done
                               :gen.job/artifacts [(str id ".vrm")]})
    {:gen.job/id id}))

(defn- fake-poll [job-id] (get @fake-jobs job-id))

(deftest submit-then-settle-accepts-a-clean-winner
  (let [tmp (tmp-dir)
        pub (publisher/mock-publisher)]
    (with-tmp-paths tmp
      (fn []
        (with-redefs [murakumo/submit! fake-submit!
                      murakumo/poll fake-poll
                      murakumo/fetch-artifact! (fn [_job] (byte-array [1 2 3]))
                      judge/score! (fn [_c _p] {:score 77 :critique "fine"})
                      generate/input-mesh-cid (constantly "bafyrig-mesh-1")
                      shell/sh (fn [& _args] {:exit 0 :out "" :err ""})]
          (reset! fake-jobs {})
          (testing "first tick submits a round"
            (is (= :submitted (loop/tick!))))
          (testing "second tick (jobs already :done in the fake) settles it"
            (is (= :settled (loop/tick! pub))))
          (testing "ledger recorded an accepted asset"
            (let [facts (ledger/read-all (str tmp "/ledger.edn"))]
              (is (some #(= :accepted (:t %)) facts))))
          (testing "publisher received the record"
            (is (= 1 (count @(:a pub))))))))))

(deftest lease-prevents-concurrent-ticks
  (let [tmp (tmp-dir)]
    (with-tmp-paths tmp
      (fn []
        (spit (str tmp "/lease") (str (System/currentTimeMillis)))
        (is (= :lease-held (loop/tick!)))))))

(deftest budget-caps-daily-submissions
  (let [tmp (tmp-dir)]
    (with-tmp-paths tmp
      (fn []
        (with-redefs [murakumo/submit! fake-submit!
                      generate/input-mesh-cid (constantly "bafyrig-mesh-1")]
          (spit (str tmp "/state.edn")
                (pr-str {:round 0 :day (str (java.time.LocalDate/now))
                         :submitted-today 999 :pending [] :elite-gene nil
                         :round-started-at nil}))
          (is (= :idle (loop/tick!))))))))

(deftest no-input-mesh-cid-submits-zero-candidates-not-a-broken-job
  (testing "HONEST LIMIT: RIG_INPUT_MESH_CID unset -> round-candidates is
           empty -> murakumo/submit! is never called -> :pending stays empty
           (no crash, no broken unirig job submitted without :refs)"
    (let [tmp (tmp-dir)]
      (with-tmp-paths tmp
        (fn []
          (with-redefs [murakumo/submit! (fn [_c] (throw (ex-info "must not be called with no mesh cid" {})))
                        generate/input-mesh-cid (constantly nil)]
            (is (= :submitted (loop/tick!)))
            (is (empty? (:pending (loop/load-state))))))))))
