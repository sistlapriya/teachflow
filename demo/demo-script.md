# 5-minute demo script

Record on a real phone with screen recording on (the floating panel is visible in the recording). Before recording:

- Sign in to Zomato.
- Delete any old TeachFlow skills (Diagnostics → Reset skills).
- Empty the Zomato cart.
- Have a way to make a popup appear, e.g. open the app fresh when a promo is running. Otherwise skip scene 6, or use "item already in cart", which shows the same recovery path.

Every scene must be a real run. If a scene fails on camera, keep it and show the trace. Stopping safely and explaining why is part of the product.

| Time | Scene | Say / do | Show on screen |
|---|---|---|---|
| 0:00–0:20 | Intro | "TeachFlow learns what you're trying to do, not where you tapped." | TeachFlow Home · LIVE PROTOTYPE chip |
| 0:20–1:10 | **1. Teach** | 🎙 "Order a Margherita pizza from Domino's on Zomato." → **Teach new skill** → search Margherita → Domino's → ADD → View cart → Stop teaching | Overlay **● LEARNING** with the observed count → Review: **LEARNED**, parameters ITEM / RESTAURANT / QUANTITY / ADDRESS, semantic actions, safety boundary PAYMENT, relevance trace (if a call came in: **IRRELEVANT · DISCARD**) → **Save** |
| 1:10–1:45 | **2. Exact replay** | Same command → **Run** | Match reasons → app opens → steps in the overlay → **🛡 PAYMENT SCREEN DETECTED · YOUR TURN** |
| 1:45–2:15 | **3. Paraphrase** | "Get me a margherita from dominos." | Home: *same app · same action type · same RESTAURANT* → runs to payment |
| 2:15–2:45 | **4. Changed slot** | "Order a Farmhouse pizza from Domino's." | Trace: TARGET GROUNDED "ADD · Farmhouse Pizza" · Next to {ITEM} ✓ · Farmhouse in the cart |
| 2:45–3:15 | **5. Quantity** | "Order two Margherita pizzas from Domino's." | SET QUANTITY → STATE VERIFIED: *quantity shows 2* |
| 3:15–3:45 | **6. UI change** | Run with a popup (or item already in cart) | Overlay **Recovery 1 / 3** → STATE CLASSIFIED POPUP → dismissed → continues. Report: recovered 1 |
| 3:45–4:20 | **7. Stuck** | "Order Pepperoni Supreme from Domino's." | Recovery 1/3 → 2/3 (scroll) → 3/3 → **RECOVERY EXHAUSTED · 3 / 3 · SAFE STOP · USER INPUT REQUIRED** + the specific question → Stop → *NO SAFE MATCH AFTER 3 RECOVERY ATTEMPTS* |
| 4:20–4:45 | **8. Safety** | Any run reaching payment (or signed out → sign-in) | **🛡 SAFETY BOUNDARY · YOUR TURN**. "TeachFlow never types credentials or pays." |
| 4:45–5:00 | Close | History → open a run report and trace | Run report + clickable trace. "Teach once. Say it naturally. Let Android act." |

Backup if time allows (not in the 5 minutes): Amazon teach and replay (T8 and T9), "Book a cab" (T12), "Order pizza" (T13).
