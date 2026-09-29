# Presentation content (16 slides)

On-slide text is kept short. Speaker notes follow each slide. **Slide 12 must only contain measured results**: fill it from the APK's exported results after device testing.

---

### 1 · Title
**TeachFlow**
Teach once. Say it naturally. Let Android act.
*Samsung PRISM Generative AI Hackathon 2026–27 · [team names]*

> Notes: One sentence: an Android agent that learns a task from one demonstration and replays it from natural language, safely.

### 2 · Problem
Voice assistants understand commands but can't reliably **operate** third-party apps.
"Order my usual pizza" still means 12 taps across changing screens.

> Notes: Assistants stop at intent. The last mile, operating a real app's UI, is where they fail.

### 3 · Existing gap
| Traditional automation | TeachFlow |
|---|---|
| Screen coordinates | Demonstration |
| Hand-written scripts | Semantic workflow |
| App-specific integrations | Dynamic grounding on the live UI tree |

> Notes: Coordinates break when a button moves, and scripts need a programmer. We learn from what the user already knows how to do.

### 4 · Solution
Teach once → Generalize → Replay → Recover → Stop safely.

> Notes: Every stage is visible to the user: a review before saving, a trace during the run, a report after.

### 5 · How it works
Voice → intent → slots → skill → accessibility tree → grounding → action → verification

> Notes: All on the device, offline. No app APIs, no deep links, no coordinates.

### 6 · Architecture
*(Use `docs/screenshots/web/06-architecture.png` or the Mermaid diagram in `docs/architecture.md`.)*
Safety branch: LOGIN / OTP / PAYMENT → Safety Guardian → YOUR TURN.

> Notes: The Safety Guardian is deterministic and runs before every step, before every tap, and on the screen each tap produces.

### 7 · Teaching
Demonstration → generalized workflow:
`OPEN SEARCH → SEARCH {ITEM} → SELECT RESTAURANT {RESTAURANT} → ADD {ITEM} TO CART → OPEN CART → STOP_AT_PAYMENT`
Irrelevant actions (a phone call, a detour, a popup) are discarded, with reasons.

> Notes: The relevance filter is not a list of known distractions. It uses task context, a semantic link to the command, and state-transition contribution.

### 8 · Generalization
Margherita → Farmhouse · 1 → 2 · Home → Work · wireless earbuds → phone case
*Same workflow, new parameters. No relearning.*

> Notes: Values from the command are located in what the user typed and tapped; those elements become slot references.

### 9 · Adaptation
Popup → dismissed safely · Already in cart → skipped and verified · Target moved → scroll and re-ground.
Max 3 attempts per step, then **SAFE STOP** with a specific question.

> Notes: Recovery depends on the classified state. When TeachFlow can't be sure, it asks instead of guessing.

### 10 · Safety
Payment · OTP · password · PIN · biometric → **YOUR TURN**.
Never types, captures or submits credentials. Resume only on explicit user action.

> Notes: Credential values are redacted at capture time. The UI never implies TeachFlow paid.

### 11 · Live demo
T1 teach → T2 replay → T3 paraphrase → T4 item → T5 quantity → T7 popup → T10 stuck → T11 safety
*(Follow `demo/demo-script.md`.)*

### 12 · Results
**Only measured results from device runs.** Fill from the APK export (Tests → Export results JSON) or the website's Judge Mode.
Until then this slide says: *Device testing in progress. All T1–T14: NOT YET TESTED.*
Optionally add, clearly labelled as off-device: "Logic check: 13 / 13 scripted executor scenarios behave as designed (fake app, not a device)."

> Notes: Never round up, never estimate. If a test failed, show it and its trace.

### 13 · Limitations
English commands only · heuristic roles and verification · needs apps that expose accessibility labels · quantity and address need standard controls · cross-app transfer is experimental · device testing pending.

### 14 · Future
More complex workflows (branches, loops) · richer UI understanding (on-device vision for unlabelled views) · validated cross-app transfer · a shareable skill library · multilingual commands.

### 15 · Differentiation
**From tap replay to task understanding.**
Semantic, parameterized, verified, recoverable, and safe by construction.

### 16 · Thank you
**TeachFlow**: Teach once. Say it naturally. Let Android act.
[GitHub link] · [website link]
