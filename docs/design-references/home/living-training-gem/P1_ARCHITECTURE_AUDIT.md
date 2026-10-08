# Rocky X — Living Training Gem P1 Architecture Audit

Status: NO-GO for P1 implementation
Audit type: Existing-code architecture audit
Branch: product-stabilization-home-2026-10-07
Audited head: 2dd39155dfe9ba9156bfe123b24e0e75f2e49422

## Scope

This audit compares the current Living Gem / Home visual implementation against the approved Home reference image and the current LIVING_GEM_REFERENCE_LOCK_V1 decisions supplied for this stage.

No P1 production implementation is authorized by this audit.

## Direct findings

### A1 — ReferenceHomeVisualView is not a valid final Living Gem renderer

Current implementation renders a baked reference bitmap, adds an independently drawn screen-space pulse arc, uses a hard-coded cadence, and generates pulse nodes around an ellipse.

This is not the reference's actual network topology and is not acceptable as the production Living Gem implementation.

Required disposition: replace during P1 design/implementation. Do not extend this pulse model.

### A2 — Current LivingGemView contradicts locked reference motion

Current code contains yaw and orbitDegPerSec, state-dependent orbit speeds up to 12 deg/sec, continuous automatic pulse cadence, Rocky breathing scale/translation, and automatic blink.

Current reference lock says topology is static; micro-orbit is OFF by default; Rocky breathing/blink/yawn are UNVERIFIED; Gold Pulse is event-driven by Start/Continue only.

Required disposition: remove/disable these behaviors from the reference-aligned P1 design unless a later reference explicitly authorizes them.

### A3 — GemMesh is invented topology, not extracted reference topology

Current GemMesh is a deterministic brilliant-cut/biconvex 7-ring mesh.

The high-resolution reference audit only establishes partial extraction: outer ring; approximately 15–20 reliable gold-side vertices/edges; additional color-coded network regions; no reliable complete node/edge list yet.

Required disposition: do not promote the current GemMesh into the reference topology. P0.2 must produce canonical topology data from the reference.

### A4 — Gold Pulse is not connected to the training intent event

The Home CTA currently closes panels and calls showLesson(...). There is no playEvent(...) call at the CTA boundary and no visible trainingIntentEventId propagation in this overlay.

The locked product contract requires: Training Intent -> Start/Continue -> ROCKY_X_SIGNATURE_PULSE.

Pulse dedupe key: trainingIntentEventId. sessionId is not the dedupe key.

Required disposition: design the event boundary before P1 implementation.

### A5 — Pulse is currently in the wrong coordinate system

ReferenceHomeVisualView draws its pulse as an ellipse/arc over the screen.

The locked requirement is that the pulse travels on the same Gem edge coordinate system as the actual topology.

Required disposition: pulse path must be derived from canonical Gem edges, not an independent screen-space ring.

### A6 — Current VisualState model encodes an obsolete motion model

VisualProfile currently contains orbitDegPerSec, lightSweepHz, lightIntensity, and glow.

The first field conflicts directly with D2 static topology.

Required disposition: redesign the visual state model around topology visibility/state, color-layer intensity, light-field parameters, signature-pulse event state, and reduced-motion policy. No domain semantics belong inside the renderer.

### A7 — Current Rocky asset is explicitly prototype-only

The overlay embeds a very small temporary Rocky WebP.

This is correctly documented as a prototype asset, but it is not suitable as the final commercial Home hero.

Required disposition: keep prototype status explicit; do not treat current asset fidelity as product-ready.

### A8 — Two competing Home visual systems currently exist

The branch contains LivingGemView and ReferenceHomeVisualView. They implement different visual assumptions.

This is a major architecture risk because future fixes can diverge between renderers.

Required disposition: P1 must define one canonical reference-aligned visual architecture. A legacy/prototype renderer may remain isolated only if clearly marked non-production.

## P0.2 required before implementation

1. Produce canonical normalized coordinates for the reliably extractable topology.
2. Separate outer ring, gold network/facets, blue/teal network, orange network, nodes, labels, and Rocky occlusion mask/layer.
3. Record confidence for every extracted geometry element.
4. Do not infer semantic meaning for blue/orange from color alone.
5. Define pulse candidate edges from the gold network.
6. Mark the full pulse path/direction as UNVERIFIED until supported by reference frames.
7. Keep measured colors as reference samples, not brand tokens.

## P1 design target

Reference Master -> Canonical Gem Topology -> Visual State Mapper -> LivingGemRenderer -> Training Intent Event

Renderer layers: Back Network -> Rocky Core -> Gold/Active Facets -> Front/Highlight Edges only if reference proves them -> Light Field -> Signature Pulse.

The renderer remains presentation-only. It must not own TrainingSession, Evidence, Evaluation, Assessment, Decision, Policy, persistence, or sync.

## Pulse contract

Only START_TRAINING and CONTINUE_TRAINING may trigger the signature pulse.

Each event has a unique trainingIntentEventId.

Required behavior: one pulse per event; no pulse from scrolling, Library, More, random Home taps, or lifecycle resume; a new intent while active soft-interrupts the current pulse; no queue.

## Decision

P0.2: GO.
P1 architecture design: GO.
P1 production implementation: NO-GO until the existing-code architecture audit findings above are incorporated into the design and the canonical topology/pulse coordinate model is sufficiently defined.
APK generation for P1: NO-GO at this stage.

This audit intentionally prevents the existing prototype renderer from being mistaken for the approved product architecture.