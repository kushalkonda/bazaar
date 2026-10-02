# Verified facts about Hypixel's Bazaar

Everything here was confirmed from real data — a live API response, a real captured screen dump, or
an actual chat line in a game log. Nothing in this file is inferred, and anything that *is* an
inference is marked as one.

This exists so a rewrite doesn't have to rediscover any of it. Most of these cost a real failure to
learn, several cost real coins.

---

## 1. Hypixel Bazaar API

`GET https://api.hypixel.net/v2/skyblock/bazaar` — no API key, no per-item endpoint, returns every
product in one payload (a few MB).

**The summary field names are backwards.** `buy_summary` is the *sell-offer* book (what you'd buy
from); `sell_summary` is the *buy-order* book (what you'd sell into). Confirmed by cross-checking
`quick_status` on WHEAT and COBBLESTONE: the naive reading puts sell offers entirely below buy
orders on the most liquid products in the game, which is economically impossible. Apparently named
for the action a player takes, not the order sitting there.

`quick_status` fields:
- `buyPrice` — instant-buy cost; `sellPrice` — instant-sell proceeds. Both are **weighted averages
  of recent trades, not top-of-book**. Unusable for "am I the top order".
- `buyMovingWeek` / `sellMovingWeek` — real units traded over 7 days.

Top-of-book must be computed as max/min over the summary arrays — don't assume they're sorted.

**Confirmed real tags:** `SUMMONING_EYE`, `ENCHANTMENT_SCUBA_5`,
`ENCHANTMENT_ULTIMATE_LEGION_1`…`_5`. Enchant books all carry the `ENCHANTMENT_` prefix (773 of
2123 products). Note Legion's real tag includes `ULTIMATE_` — guessing `ENCHANTMENT_LEGION_*` is
wrong.

**SUMMONING_EYE** (checked 2026-09-27), a good test item — plain item, liquid, real spread:
instant-buy ~1,497,209 · instant-sell ~1,395,512 · 105,227 bought/wk · 72,134 sold/wk · 30-deep
both sides.

## 2. Coflnet (sky.coflnet.com) — unreliable for books

`ENCHANTMENT_SCUBA_5` resolves to `"scuba 5 enchant"`, not Hypixel's real `"Scuba V"`.
`ENCHANTMENT_TELEKINESIS_1` returns zero results. Do not trust it to produce a name you then type
into `/bz`.

Its search endpoint takes the name in the URL *path*. `URLEncoder` encodes spaces as `+`, which
this endpoint treats literally — use the multi-arg `URI` constructor so a space becomes `%20`.

## 3. The manage-orders (F6) menu

Opened with `/bz`, then clicking slot `F6`.

**Slot indices** (9-wide grid, confirmed from dumps): `A2`=9 `B2`=10 `C2`=11 `D2`=12 `E2`=13 `I2`=17;
`A3`=18 `B3`=19 `C3`=20 `D3`=21 `E3`=22 `I3`=26. Row 4 holds controls: `D4`=30 Go Back, `E4`=31
Close, `F4`=32 Claim All Coins.

**Sell offers sit on row 2, buy orders on row 3.** Multiple orders fill left to right — confirmed
2026-09-27 with two concurrent buy orders observed at `B3` *and* `C3`. (Earlier this was only an
inference; it is now observed. Still unobserved: what happens past column C, and whether cancelling
a middle order shifts the rest left.)

**An order identifies itself.** Real captured example:

```
SELL Jolly Pink Rock          <- name is "<SIDE> <item>"
Worth 120.8k coins
Offer amount: 1x              <- "Order amount: Nx" on buy orders
Price per unit: 122,354.1 coins
By: [VIP] white_toes_yum
Click to view options!
```

Read side, item, quantity and price straight off the item. Do **not** track an order by remembered
slot position.

**Empty is not empty.** The menu's decorative filler panes are non-empty items with blank display
names. "Is this slot populated" is *not* a valid test for "is there an order here" — require the
name prefix and a parseable price line.

**Price does not uniquely identify an order.** Two concurrent buy orders on the same item were
observed 0.1 coins apart. Price distinguishes orders of *different items* fine; it cannot
distinguish two orders of the *same* item.

## 4. Exact chat lines

```
[Bazaar] Putting goods in escrow...
[Bazaar] Submitting buy order...
[Bazaar] Buy Order Setup! 16x Legion I for 38,920,205 coins.
[Bazaar] Sell Offer Setup! 6x Scuba V for 1,234,567 coins.
[Bazaar] Cancelling order...
[Bazaar] Cancelled! Refunded 38,920,206 coins from cancelling Buy Order!
[Bazaar] Executing instant buy...
[Bazaar] Bought 1x Booster Cookie for 12,556,549 coins!
[Bazaar] You have goods to claim on this order!
A kick occurred in your connection, so you were put in the SkyBlock lobby!
```

The setup/claim lines carry the item's **display name** (`Legion I`), never its product tag. A
matcher fed a tag will never fire — that mistake caused a real duplicate order.

## 5. Click coordinates (verified by recording or directly by the user)

Item page: `buyInstantly` B2 · `buyOrderButton` G2 · `sellOfferButton` H2 · `amountField` H2 ·
`confirmInstant` E2 · `orderPricePreset` D2 · `sellPricePreset` D2 · `orderConfirm` E2.
Manage orders: `claimOrdersMenu` F6 · `buyClaimSlot` B3 · `sellClaimSlot` B2 · `cancelButton` C2
(on the order's own management screen, *not* a column of the list).

Search results occupy slots 10–42. Their lore shows `Buy price: X coins` / `Sell price: Y coins`,
swapped the same way the API is: the shown "Buy price" lines up with the top *sell* offer.

**Buy flow:** `/bz <name>` → click the matching result → `buyOrderButton` → `amountField` → type
quantity into the sign → `orderPricePreset` → `orderConfirm`.

**Sell flow is genuinely different** — not just different slots. Going through `/bz` search leads to
a manual price prompt with no preset. The working path is to click the item **in the player's own
inventory** (visible at the bottom of any Bazaar screen), which jumps straight to the same D2 preset
for the player's *entire* held stack. There is no quantity step, so you cannot sell part of a stack
this way.

The D2 "Top Order" preset sets the real price **server-side at click time**. Whatever price you
computed beforehand is an estimate; read the order's real price back from its own lore.

## 6. Item NBT

SkyBlock id lives at the **top level** of the `CUSTOM_DATA` component: `{id:"ENCHANTED_WHEAT"}`.
There is no `ExtraAttributes` wrapper — assuming one silently matches nothing.

Enchant books are `{enchantments:{scuba:1},id:"ENCHANTED_BOOK"}`. **Every unapplied enchant book
shares the display name "Enchanted Book"**, so books can only be told apart by that NBT compound,
never by name. The mapping from a tag's name segment to the NBT key has only ever been
independently confirmed for Scuba (`"scuba"`) — don't assume it generalises.

Purse is parsed from the scoreboard sidebar's `Purse:` line.

## 7. Anvil (`/av`)

Combine `E3`, take result `E2`. Books are shift-clicked (`QUICK_MOVE`) from wherever they happen to
sit — crafted items land in whatever slot is free, so never use a remembered slot. The merged result
lands **on the cursor**, and gets placed back into the slot the first input vacated.

Scan floor is slot **54**: below that are the anvil's own input indicators, and a book shift-clicked
into one still reads as a real match there, so a naive scan re-selects the book it just placed.

The anvil lags heavily — every click needs a long settle (~1.5s) and a retry-with-wait before any
negative conclusion.

## 8. Failure modes that actually happened

1. **The D2 price-preset click does nothing via the normal slot-click path.** Byte-identical screen
   before and after, regardless of wait. Must go through the real mouse-input path. Unique to that
   screen so far.
2. **Render lag reads a populated screen as empty**, repeatably, right after other actions — and it
   persisted across consecutive re-opens, so one extra check isn't enough. Always retry with a
   longer wait before concluding anything is gone.
3. **The macro runner reports failure on genuinely successful click sequences** (most likely when
   the window is unfocused and the UI updates slower than the fixed waits assume). A real 275,947
   coin sell offer was orphaned by trusting that verdict. Never trust it alone — confirm against
   real state.
4. **Hypixel refuses to cancel an order with unclaimed goods on it.** Claim first, then retry.
5. **Rate limiting kicks you to the SkyBlock lobby** ("Sending packets too fast!"). The client stays
   connected, so a disconnect check won't catch it — everything then fails until you warp back.
6. **A queue drain that discards non-matches destroys evidence for every later matcher.** This made
   a successful order look failed and get placed twice, escrowing 38.9M coins twice. Consume only
   what you match.
7. **Purse deltas are not attributable.** A talisman, a booster or a mob drop is indistinguishable
   from Bazaar income. Coins may only ever be credited from Hypixel's own claim confirmation.
8. **Inventory reads are momentarily stale right after a screen closes.** Settle before measuring.

## 9. Things still genuinely unknown

- Whether orders past column C follow the same left-to-right layout.
- Whether cancelling a middle order shifts later ones left.
- How to distinguish two orders of the *same* item at the *same* price.
- The real wording of the buy-order amount lore line (`Order amount:` is assumed to mirror the
  confirmed sell-side `Offer amount:`).
- Instant-sell's click flow — never verified.
