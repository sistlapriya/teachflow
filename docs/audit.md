# Final audit

Environment: no Android device or emulator, and no Google Maven access (so no Compose libraries). Every result below says how it was checked. **Nothing here is a device result.**

| Method | Meaning |
|---|---|
| **COMPILED** | Compiled with Kotlin 2.0.21 against `android.jar` (API 33), with coroutine stubs: 27 engine files, 0 errors. |
| **LOGIC** | Ran on a JVM in `android/tools/logic-check` (synthetic screens, and the real `WorkflowExecutor` driving a scripted fake app). |
| **BUILT** | The whole app (engine + Compose UI) built into an APK by GitHub Actions (`.github/workflows/build-apk.yml`). |
| **BROWSER** | Website exercised in headless Chromium. |
| **NOT TESTED** | Requires a physical Android device. |

## Results by area

| Area | Check | Result |
|---|---|---|
| Functionality / engine | COMPILED | ✅ 0 errors |
| UI (Compose) + full app | **BUILT** on GitHub Actions | ✅ `gradle assembleDebug` succeeded on the first build (run 36613132718); the APK contains the service, executor, safety guardian, answer screen and Judge Mode, and declares `BIND_ACCESSIBILITY_SERVICE` |
| Android / accessibility | COMPILED + manifest audit | ✅ service declared with `BIND_ACCESSIBILITY_SERVICE`; both activities declared; `AnswerActivity` started with `FLAG_ACTIVITY_NEW_TASK` · **NOT TESTED** on a device |
| Teaching + relevance | LOGIC | ✅ phone call → IRRELEVANT·DISCARD · detour → IRRELEVANT · no-effect tap → UNCERTAIN (kept, flagged) |
| Generalization / parameters | LOGIC | ✅ typed "Margherita" → `{ITEM}`; ADD next to the right item for Margherita / Farmhouse / Garlic Bread; quantity verified = 2; Work address selected |
| Replay / paraphrase | LOGIC | ✅ exact 1.00, "Get me a margherita from Domino's" 0.78, "I want to order margherita pizza on Zomato" 0.80; "Book a cab" and a wrong-app command → NO MATCH |
| Recovery | LOGIC | ✅ popup dismissed and recovered; already-in-cart skipped; missing item → 3/3 → RECOVERY EXHAUSTED + specific question; empty results → ask |
| Safety | LOGIC | ✅ payment → PAYMENT_BOUNDARY; login → pause → Resume → re-verify (the action isn't repeated); the harness fails on any credential or payment tap, and **0 violations** occurred |
| Unknown intent | LOGIC (matcher) | ✅ NO MATCH → UNKNOWN INTENT (UI path: SYNTAX) |
| Ambiguity | LOGIC | ✅ "Order pizza from Domino's" → asks which pizza → PARAMETER RESOLVED; "Order a Margherita" → asks restaurant mid-run (typed answer and taught option both tested) |
| Reporting | LOGIC | ✅ every scenario produces a terminal reason, counts and a trace; the APK export matches the website importer's schema (BROWSER round-trip with a local format-test file, not published) |
| Persistence | COMPILED | ✅ skills.json / runs.json with atomic writes · **NOT TESTED** across a real app restart |
| Website | BROWSER | ✅ 41 anchors, none broken; no script errors; Judge Mode 14 rows, all NOT YET TESTED; import/clear works; all 11 disruptions run; no horizontal overflow at 390 px |
| GitHub | Secret scan | ✅ no keys, tokens or credentials; `.gitignore` excludes builds, APKs and keystores |
| Hardcoding | Source scan | ✅ no app names, test commands or coordinates in execution logic (only an input placeholder and doc comments) |
| README / docs / demo / presentation | Review | ✅ written; device-dependent items marked NOT YET TESTED |

## Bugs found and fixed in this pass

1. **The logic-check harness no longer compiled.** A previous change moved the executor's question API to `Reply` (typed answers), but the scenarios still used the old `Int` API, so `run.sh` failed. Ported to `Reply`. Added two scenarios that exercise mid-run clarification (typed answer, and taught option).
2. **The relevance check was never run.** `RelevanceCheck.kt` existed but wasn't wired into `run.sh`. Wired in; it passes.
3. **Missing cross-app experiment (Part S).** The website listed cross-app transfer as EXPERIMENTAL, but the APK had no way to try it. Added **Try in another app** (Skills): the same semantic steps are grounded against the chosen app. The trace and report say EXPERIMENTAL, and the run is never counted in skill statistics.
4. **Step names didn't match the spec's semantic actions.** Workflows now read `OPEN SEARCH → SEARCH {ITEM} → SELECT RESTAURANT {RESTAURANT} → ADD {ITEM} TO CART → SET QUANTITY → OPEN CART → SELECT ADDRESS → STOP_AT_PAYMENT`. The website playground now uses the same names.
5. **Duplicate code introduced during this audit** (a second `status` field and duplicate relevance and session helpers). Removed before the final compile.

Earlier passes (kept for the record): verification treated any screen change as success (a login screen passed "cart opens"), now fixed. Several grounding bugs were also found by the logic harness and fixed: the prefix item match, the proximity tie, "first result" learned by title instead of position, and asking before scrolling.

## Not tested: requires a physical Android device

Everything that touches real apps:

- teaching in Zomato and Amazon
- grounding on their real accessibility trees
- the overlay and AnswerActivity on screen
- voice input
- safety detection on real payment and login screens
- persistence across restarts
- all of T1–T14

