# Commands to copy or say

## Teach

- Order a Margherita pizza from Domino's on Zomato.
- Search for wireless earbuds on Amazon and add the first result to cart.

## Replay and generalize

| Test | Command |
|---|---|
| T2 exact | Order a Margherita pizza from Domino's on Zomato. |
| T3 paraphrase | Get me a margherita from dominos. |
| T3 paraphrase (asks restaurant) | I want to order a margherita pizza on Zomato. |
| T4 item | Order a Farmhouse pizza from Domino's. |
| T5 quantity | Order two Margherita pizzas from Domino's. |
| T6 address | Order a Margherita from Domino's, deliver to work. |
| T9 new search term | Search for a phone case on Amazon and add the first result to cart. |

## Try to break it

| Disruption | How | Expected |
|---|---|---|
| Change item | Order a Farmhouse pizza from Domino's. | ADD next to Farmhouse |
| Change quantity | Order two Margherita pizzas from Domino's. | quantity verified = 2 |
| Change address | …deliver to work | Work selected |
| Add popup | run while a promo popup shows | POPUP → dismissed → continues |
| Item already in cart | add Margherita first, then run T2 | ALREADY_COMPLETED → skipped |
| Change language | a non-English command | not supported: English only (asks, doesn't guess) |
| Remove target | Order Pepperoni Supreme from Domino's. | 3 / 3 → SAFE STOP → specific question |
| Trigger login | sign out of the app, then run | AUTHENTICATION REQUIRED · YOUR TURN → Resume |
| Trigger payment | any full run | PAYMENT SCREEN DETECTED · YOUR TURN |
| Unknown command | Book a cab to the airport. | UNKNOWN INTENT · safe no-op · Teach me |
| Ambiguous command | Order pizza. | "Which pizza would you like?" |
