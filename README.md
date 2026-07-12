# gftd-rig-actor

A **free auto-rig generation** loop actor for
[`network-isekai`](https://github.com/gftdcojp/network-isekai), gftdcojp's
third of seven per-modality asset actors (ADR-2607122200). Persona: **セキ
(Seki)**, 骨師 (rigger) — "骨と皮を繋ぐ職人。動きが自然に伝わる骨格だけを通す
— 派手さより関節の可動域を信じる" (see `resources/persona.edn`). Sibling
actors: `gftd-illust-actor` (image), `gftd-sculpt-actor` (3D mesh),
`gftd-motion-actor` (motion clips), `gftd-avatar-actor` (VRM compositing),
`gftd-audio-actor` (music+SFX), `gftd-voice-actor` (TTS voice).

Built on the same "sealed intelligence ⊣ independent governor ⊣ append-only
ledger" containment pattern as this workspace's other actors
(`gftd-talent-actor`, `wami-actor`, `cloud-itonami`, `gftd-illust-actor`) —
here it is **co-scientist tournament ⊣ AssetGovernor**, run by a **durable
outer loop** (not a StateGraph — murakumo generation jobs are async,
minutes-scale, and this workspace's CLAUDE.md is explicit that long-running
work belongs in a lease/tick/budget loop, not a StateGraph interrupt).

## The core contract

```
rig.generate               murakumo fleet (async gen.job)      rig.judge
 (closed preset pool, ──▶  submit via cloud-murakumo.gen +  ──▶ (persona-fit
  persona-flavored)        queue-kotoba, poll for :done)         preset score)
                                    │
                                    ▼
                          rig.cosci/run-round
              (Reflection=HARD gate, Ranking=Elo on judge score,
                    Proximity, Evolution, Meta-review)
                                    │
                              round winner
                                    ▼
                          rig.governor/violations
                    (license-free? format-ok? safe? titled?
                          write-kind is :asset only)
                          │                    │
                        ok?                  hard
                          ▼                    ▼
            rig.datalad + rig.aozora        rig.ledger
          (save to assets/, datalad push,   (:held — no binary
             publish to net.rig.asset)       is ever saved)
```

**The actor never commits/publishes an asset the AssetGovernor would
reject**, and it never writes anything but `:kind :asset` — it does not
touch network-isekai's game logic or canon, it only produces free material
for games to consume.

## NOT text-to-X: this actor rigs an EXISTING mesh

Unlike `gftd-illust-actor` (a pure text-prompt generator), auto-rigging is
not generative-from-nothing. `:autorig`'s engine is `:unirig`
(`cloud-murakumo`'s `resources/murakumo.edn` — `:fn/engine :unirig
:fn/modality :rig`, ADR-0048 §2): it takes an EXISTING glb/vrm mesh as input
(`:refs [cid]`) and produces a rigged VRM. So `rig.generate/round-candidates`
does **not** vary subject/style/lighting text — it varies the **rigging
preset/config** applied to one fixed input mesh:

- `:rig-type` — `"humanoid"` / `"quadruped"` / `"prop-armature"`
- `:symmetry` — `"strict"` / `"relaxed"`
- `:bone-density` — `"minimal"` / `"standard"` / `"detailed"`

and every candidate's `:params` carries `:refs [input-mesh-cid]`, where
`input-mesh-cid` is resolved once per `round-candidates` call from the env
var `RIG_INPUT_MESH_CID` (a single CID string). `rig.murakumo/submit!` passes
that straight through to `cloud-murakumo.gen/job`'s request map (`gen/job`
already accepts a top-level `:refs` key — `cloud-murakumo` itself needed no
changes).

**HONEST LIMIT (state this, do not pretend otherwise): this actor cannot
generate a rig from nothing.** It needs `RIG_INPUT_MESH_CID` set to an
existing mesh — e.g. a CID from `gftd-sculpt-actor`'s own accepted output.
If that env var is unset, `rig.generate/round-candidates` returns an EMPTY
vector (0 candidates) rather than submitting a broken unirig job with no
`:refs`; `rig.loop/submit-round!`'s existing budget/pending logic already
tolerates a round with 0 submitted candidates (nothing gets submitted that
tick, the next tick just tries again). **Wiring an automatic hand-off from
`gftd-sculpt-actor`'s own output into `RIG_INPUT_MESH_CID` is explicit
follow-up work, not built here** — today an operator (or a future pipeline
script) must set the env var by hand before this actor's loop can do
anything.

**Other HONEST LIMITS** (state these, do not pretend otherwise):
- `rig.judge` scores the candidate's **preset description text**
  (rig-type/symmetry/bone-density) for persona-fit, not the actual rigged
  VRM geometry/bone weights/skin weights the generation job actually
  produced. A real structural/perceptual judge (skeleton-validity check, a
  vision-capable critique call) is follow-up.
- Whether a submitted job ever leaves `:queued` depends on a murakumo fleet
  worker (Mac-mini / `gad`) being up and consuming the `gftd-murakumo` kotoba
  queue — this actor only submits/polls, it never runs GPU inference itself.
- `rig.murakumo/artifact-url`'s CID→URL resolution is a best-effort guess
  (`KOTOBASE_ARTIFACT_BASE_URL` overrides it), not a confirmed contract.

## This repo IS its own DataLad dataset

Unlike a typical actor repo, `assets/` here is **git-annex + Backblaze B2**
(`-c text2git`: code/EDN stay plain git, binaries get annexed) — accepted
assets are saved straight into this repo and pushed to B2, so "actor's own
git repo" and "asset storage" are the same thing (ADR-2607122200 §5).
`assets/<id>.edn` is written in the `network-isekai` `isekai.asset` manifest
shape so a later Asset Hub import needs no conversion.

```sh
datalad get assets/            # fetch real bytes from B2 (skeleton clones without them)
datalad push --to b2           # push new bytes after a local save
```

## Running

```sh
RIG_INPUT_MESH_CID=<cid-of-an-existing-mesh> clojure -M:run tick   # one durable-loop step (cron/launchd)
RIG_INPUT_MESH_CID=<cid-of-an-existing-mesh> clojure -M:run run    # stay resident, tick on an interval
clojure -M:run status   # print ledger tail + loop state
clojure -M:test         # offline, fully faked (no network) — see test/rig/loop_test.clj
clojure -M:lint         # clj-kondo, errors fail
```

Without `RIG_INPUT_MESH_CID` set, `tick`/`run` still work (lease/budget
bookkeeping proceeds normally) but submit 0 candidates every round — see
HONEST LIMIT above.

Env: `RIG_INPUT_MESH_CID` (REQUIRED for real generation — see above),
`ASSET_ACTOR_DAILY_BUDGET` (default 8 gen jobs/day),
`MURAKUMO_KOTOBA_URL`/`MURAKUMO_KOTOBA_GRAPH`/`MURAKUMO_KOTOBA_TOKEN`
(queue-kotoba auth), `MURAKUMO_GATEWAY_URL` (judge's chat-completions
gateway).

CACAO identity is self-minted to `.rig/identity.edn` on first run
(gitignored — never commit a private key). aozora collection:
`net.rig.asset.publish`.

## Design

ADR-2607122200 (`network-isekai 向け murakumo 生成アセット持続ループ actor
群`) is the SSoT for this actor and its six siblings. Direct code ancestry:
`gftd-illust-actor` (actor #1, the reference this repo is a faithful port
of, modulo the mesh-input/preset-not-prompt difference above),
`cloud-itonami`'s `src/cloud_itonami/media/{murakumo,aozora,cacao,publisher,
publish}.clj(c)` (murakumo→governor→aozora pipeline), and
`cloud-murakumo`'s `src/cloud_murakumo/cosci.cljc` (co-scientist tournament
shape).

### A note on `rig.murakumo`'s `modality`

`cloud-murakumo`'s `resources/murakumo.edn` keys this actor's engine entry
`:autorig` under `:apps :generation :functions` (matching ADR-2607122200's
persona table and this actor's own domain name), but that entry's
`:fn/modality` value is `:rig`, not `:autorig`
(`:fn/engine :unirig :fn/modality :rig`). `cloud-murakumo.gen/fn-for-modality`
resolves by `:fn/modality`, so `rig.murakumo/modality` is `:rig` — using
`:autorig` there would make `rig.murakumo/function` always throw. This was
caught during this repo's own lint+test verification pass (STEP 5), the same
way the reference `gftd-illust-actor`'s two real bugs (the `cosci/rank`
comparator and `illust.datalad`'s `:artifact-bytes` key) were caught — see
Deviations below.
