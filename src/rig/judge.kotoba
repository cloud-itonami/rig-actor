(ns rig.judge
  "JVM I/O edge: score a candidate for persona-fit/technical quality via the
  murakumo chat gateway (same synchronous OpenAI-chat-completions proxy
  `cloud_itonami.media.murakumo` uses — `bb murakumo infer gateway`,
  MURAKUMO_GATEWAY_URL to override).

  HONEST LIMIT (state this, do not pretend otherwise — same discipline as
  cloud_itonami.media's own docstring, and as illust.judge's): this judges
  the CANDIDATE'S 3D AUTO-RIG CONFIGURATION DESCRIPTION (rig-type/symmetry/
  bone-density preset text) for persona-fit and plausibility, not the actual
  rigged VRM geometry/bone weights the generation job produced. A real
  perceptual/structural judge (skeleton-validity check, a vision-capable
  critique of the rigged mesh) is follow-up work — see ADR-2607123000
  Consequences."
  (:require [json.compat :as json]
            [kotoba.lang.text :as str]
            [kotoba.net.jvm-host :as jvm-host])
  )

(defn- gateway-url [] (or (System/getenv "MURAKUMO_GATEWAY_URL") "http://localhost:8790"))

(def default-model
  "Never hardcode elsewhere (llm-model-ssot discipline, root CLAUDE.md)."
  (or (System/getenv "MURAKUMO_MEDIA_MODEL") "gemma4:12b-it-qat"))

(defn jvm-http-fn [url body-str]
  ;; delegated to kotoba.net.jvm-host (the workspace's single java.net.http site)
  ((jvm-host/http-transport {:timeout-seconds 60})
   {:url url :method :post :headers {"Content-Type" "application/json"}
    :body body-str}))

(defn- extract-content [parsed] (get-in parsed ["choices" 0 "message" "content"]))

(defn parse-score
  "First 0-100 integer in the critique text, or nil (never a fabricated
  default) if the model didn't emit a parseable one."
  [text]
  (when text
    (some (fn [s] (let [n (parse-long s)] (when (and n (<= 0 n 100)) n)))
          (re-seq #"\d{1,3}" text))))

(defn score!
  "candidate + persona -> {:score 0-100 :critique \"...\"} or nil on ANY
  failure (gateway unreachable, non-200, unparseable) — rig.cosci/reflect
  must treat nil as 'skip this candidate, do not crash the round', never as
  a silently-fabricated 0."
  ([candidate persona] (score! candidate persona {}))
  ([{:keys [prompt]} {:keys [voice tags]}
    {:keys [http-fn model url] :or {http-fn jvm-http-fn model default-model}}]
   (try
     (let [url (or url (gateway-url))
           sys (str "You are " voice " Rate the following 3D auto-rig "
                    "configuration (a rig-type/symmetry/bone-density preset "
                    "applied to an existing mesh) for fit to your own tags ("
                    (str/join ", " tags) ") on a 0-100 scale. "
                    "Reply with the integer score first, then one short sentence why.")
           body {:model model :stream false
                 :messages [{:role "system" :content sys}
                            {:role "user" :content prompt}]}
           resp (http-fn (str url "/v1/chat/completions") (json/generate-string body))]
       (when (= 200 (:status resp))
         (let [content (extract-content (json/parse-string (:body resp)))]
           (when-let [score (parse-score content)]
             {:score score :critique (str/trim content)}))))
     (catch Exception _ nil))))
