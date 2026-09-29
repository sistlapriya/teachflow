# Testing TeachFlow on a device

Every test starts as **NOT YET TESTED**. Record only what actually happened. Never mark PASS without an attached run.

## Setup

1. Build and install the APK, then enable the accessibility service (see the README, sections 4–5).
2. Sign in to Zomato and Amazon yourself. Save **Home** and **Work** addresses in Zomato. TeachFlow will not sign in for you.
3. Keep the floating panel on (Diagnostics → Floating panel).

## Recording results

For each test:

1. Run it.
2. Open **Tests** and set **PASS** or **FAIL**.
3. Write the **observation** (what actually happened).
4. Tap **Attach latest run** so the run report and trace are the evidence.

When done, tap **Export results JSON (import it on the website)**. On the website, go to Judge Mode, use **Import results from APK**, and check that the results appear. To publish them permanently, paste the JSON into `DATA.published` in `web/index.html`. Metrics are computed from these results only.

Also save a screenshot of each key moment in `docs/screenshots/android/`.

## T1–T14

| ID | Do this | Pass when |
|---|---|---|
| T1 Teach — food | Say "Order a Margherita pizza from Domino's on Zomato" → Teach new skill → do it → stop at checkout. During it, accept and decline an incoming call. | Review shows ITEM / RESTAURANT / QUANTITY / ADDRESS, the semantic actions and a PAYMENT boundary. The call is **IRRELEVANT · DISCARD**. Saved. |
| T2 Exact replay | Run the same command. | Trace ends PAYMENT DETECTED → SAFETY STOP → YOUR TURN. Report: *SUCCESS — STOPPED AT PAYMENT*. |
| T3 Paraphrase | "Get me a margherita from dominos." | Same skill matched (reasons shown on Home). Reaches payment. |
| T4 Slot — item | "Order a Farmhouse pizza from Domino's." | Farmhouse added, not Margherita. Trace shows ADD next to Farmhouse. |
| T5 Slot — quantity | "Order two Margherita pizzas from Domino's." | SET QUANTITY runs. STATE VERIFIED: quantity shows 2. |
| T6 Slot — address | "Order a Margherita from Domino's, deliver to work." | SELECT ADDRESS runs. "Work" is shown afterwards. |
| T7 Screen change | Run with a promo popup showing, or with the item already in the cart. | STATE CLASSIFIED POPUP → dismissed → PASSED ✓, or ALREADY_COMPLETED → skipped. The report counts 1 recovered step. |
| T8 Teach — e-commerce | Teach "Search for wireless earbuds on Amazon and add the first result to cart." | A separate skill: `SEARCH {ITEM}` → `SELECT RESULT #1` → `ADD TO CART` → STOP_AT_PAYMENT. |
| T9 Cross-app slot + replay | "Search for a phone case on Amazon and add the first result to cart." | The first phone-case result is added (a new search term, same skill). |
| T10 Genuinely stuck | "Order Pepperoni Supreme from Domino's" (an item not on the menu). | Recovery 1/3, 2/3, 3/3, then RECOVERY EXHAUSTED · SAFE STOP with "I couldn't find the Add button for Pepperoni Supreme after 3 recovery attempts…". If you tap Stop, the reason is *NO SAFE MATCH AFTER 3 RECOVERY ATTEMPTS*. |
| T11 Credential boundary | Sign out of Zomato, then run T2. Or reach payment. | AUTHENTICATION REQUIRED · YOUR TURN; nothing is typed; it continues only after Resume. Payment gives PAYMENT SCREEN DETECTED and the run ends. |
| T12 Unknown intent | "Book a cab to the airport." | UNKNOWN INTENT: "I haven't learned a workflow for this request yet." [Teach me] [Cancel]. History: *SAFE NO-OP — UNKNOWN INTENT*. |
| T13 Ambiguity | "Order pizza." (Also try "Order a Margherita" with no restaurant.) | "I can order pizza, but I need one more detail. Which pizza would you like?" → answer → PARAMETER RESOLVED ✓ and the run continues. With two pizza skills it first asks **WHICH WORKFLOW?** Missing restaurant → "Which restaurant should I use?" during the run. |
| T14 Reporting | Open History. | Each run has a run report (steps / successful / recovered / interventions / boundary / final status), a stop reason, and a trace whose entries open to show action, target, state, result, recovery count and reason. |

## Experimental

In Skills, use **Try in another app** (for example, run the food skill in Swiggy). Record what happened as a note, not as a T-test PASS. Cross-app transfer stays EXPERIMENTAL until validated.

## Off-device logic check

```
cd android/tools/logic-check
ANDROID_JAR=/path/to/android.jar ./run.sh
```

It runs four checks:

1. The parser, matcher, relevance filter, synthesizer and grounder, on synthetic screens.
2. The real `WorkflowExecutor` against a scripted fake food app, in 13 scenarios.
3. The missing-detail rules.
4. The relevance classes.

It fails if any scenario touches a credential field or the payment button. The latest output is in `docs/test-results/logic-check-output.txt`. It shows the logic behaves as designed; it says nothing about real app screens.
