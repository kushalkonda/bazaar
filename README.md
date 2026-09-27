# Bazaar Macro (`/bfm`)

A standalone Fabric mod for Hypixel Skyblock (Minecraft 26.1.2). `/bfm` opens
`BazaarMacroScreen`, a single Aether-styled panel with a left sidebar and three pages:

1. **Bazaar Macro** - the primary experience. Pick one item, an optional purse cap, hit
   Start: it places buy orders, collects them as they fill, places sell orders once enough
   has accumulated, and loops.
2. **Craft Flip Macro** - not built yet. Will craft a configured list of items from
   configured storage spaces, then flip the results - currently just a placeholder page.
3. **Bazaar Scanner** - ranks every Bazaar product by real, liquid flip margin, with a
   one-click "Flip" button per row that jumps back to the Bazaar Macro page with that
   item's tag pre-filled.

A separate **general macro editor** still exists for anything the Flipper doesn't cover
(build your own named, ordered step sequences by hand or by recording real gameplay) - it's
no longer linked from the main screen, but stays fully usable via `/bfm macros`.

The whole UI's color scheme is swappable - five presets (Purple/Pink, Midnight Blue, Emerald,
Crimson, Slate) via small swatches at the bottom of the sidebar, reachable from any page. The
choice persists across restarts (`theme.json` in the mod's config folder).

It shares no code with, and is not coordinated by, the `aether` mod - only its proven
patterns (slot clicking, screen-title matching, the NanoVG rendering setup, and this latest
pass's sidebar/panel layout conventions) were reused as a starting point.

## Install

1. Install [Fabric](https://fabricmc.net/use/installer/) for `26.1.2`.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api/versions?g=26.1.2).
3. Build the jar (`./gradlew build`, output in `build/libs/`) or grab a release, and drop
   it into `.minecraft/mods`.

## Commands

- `/bfm` or `/bfm gui` - opens the Bazaar Macro screen (Bazaar Macro page by default).
- `/bfm start <ITEM_TAG> [cap]` - starts the flipper from chat (exact tag, e.g.
  `ENCHANTED_MYCELIUM` - no spaces; use the GUI for display names). `cap` is optional, a
  flat number or an expression like `50%`.
- `/bfm stop` - stops whichever is active (the flipper or a running macro).
- `/bfm macros` - opens the Advanced macro editor directly.
- `/bfm list` / `/bfm run <name>` - list/run saved macros without the GUI.
- `/bfm record start [name]` / `/bfm record stop` - records real commands/chat and slot
  clicks into a macro (see below).
- `/bfm flip check <ITEM_TAG> <buy|sell> <price>` - one-shot: is `<price>` currently the
  top order for that item/side?
- `/bfm alert add <item> <buy-order|sell-offer> <expression>` - one-shot price alert,
  independent of the Flipper (works whether or not it's running). `<expression>` may use
  `order`/`insta` (that side's order/instant-trade price, resolved once at creation time)
  plus plain arithmetic, e.g. `order - 5000000` or `order * 1.1`.
- `/bfm alert list` / `/bfm alert remove <index>` - list or cancel active alerts.
- `/bfm scan` - opens the Bazaar Macro screen straight to the Bazaar Scanner page and kicks
  off a scan (see below). The Scanner page also auto-scans the first time you switch to it.
- `/bfm history` - lists recent completed flip sessions and the all-time profit total (also
  shown at the top of the Bazaar Macro page).
- `/bfm debug inventory` - dumps every inventory slot's item id, resolved SkyBlock id, and
  raw NBT to chat/console - diagnostic for when the engine isn't recognizing an item it
  should be counting.
- `/bfm debug orders` - opens the manage-orders (F6) menu and dumps its real slot layout
  (coordinate, name, lore) to chat/console - this is what confirmed sell offers and buy orders
  sit in separate rows, which is what makes running both concurrently safe (see below).

## Finding what to flip (`BazaarScanner` / Bazaar Scanner page)

The Bazaar Scanner page fetches every Bazaar product, prices each one exactly the way
`FlipperEngine` would (`stableTopBuyPrice()`/`stableTopSellPrice()` - outlier-resistant, not
the literal extreme a single manipulated order can drag around), nets out the real
configured tax rate, and ranks by margin percent. Products with less than 50,000 coins of
depth at the top of either side are skipped - a margin that's only real for a handful of
units isn't a flip, it's a rounding error. Each row's "Flip" button switches back to the
Bazaar Macro page with that item's tag pre-filled, ready to hit Start.

## The Flipper (`dev.bazaarmacro.flipper`)

`FlipperEngine` runs a buy order and a sell offer *at the same time*, independently, on its own
background thread - not a strict buy-then-sell sequence. Every poll cycle it ticks the buy side
(watch/collect an open order, or place a new one) and then the sell side (watch/collect an open
offer, or list one) against a shared pool of "sellable stock": item claimed from filled buy
orders feeds the pool, and once the pool clears a threshold (~25% of the last buy order's
quantity, so a slot isn't spent on a handful of items) a sell offer goes out for it - all while
the buy order keeps running to accumulate more, exactly the "keep buying while also selling"
behavior a manual flipper would do by hand. Both sides share the same 7-order-slot budget and the
same 30s minimum gap between real actions. How often it checks either side isn't configurable -
it's a random 5-10 seconds every cycle, deliberately not a fixed cadence, since that reads less
like automation.

Pricing comes from Hypixel's official public Bazaar API (`HypixelBazaarClient`, no key needed) -
top buy price + 0.1 for buy orders, top sell price − 0.1 for sell offers, computed via max/min
over the raw order-book arrays (see the class doc for why: neither array index position nor
`quick_status` can be trusted for "top price"). Whether an order is actually *done* is decided by
a direct signal, not an inferred one: whether its slot in the manage-orders (F6) menu still shows
an item at all. Two earlier approaches broke under real gameplay: a delta bracketed around each
claim click got stuck forever if something else claimed a fill first (the bracket saw zero gain);
an absolute delta from a placement-time baseline fixed that but broke under real concurrent
buy+sell operation instead, since a buy order spends from the same purse a sell offer's proceeds
were being measured against (confirmed via a real session: the engine measured 9,104/10,427 coins
on a sell offer that Hypixel's own chat log confirmed paid out the full 10,427). Checking the menu
slot directly sidesteps both failure modes. Being outbid or undercut is detected the same way
`/bfm flip check` always has and reacted to by canceling that side's stale order and re-pricing
fresh, without disturbing the other side.
Before placing any order it also refuses degenerate order-book reads (a `<= 0` top price) and
prices that would cross the opposing side's top price outright (that should be an instant trade,
never an autonomous limit order), and clamps buy-order quantity against Hypixel's own stated
per-order volume cap (parsed from the amount screen's lore) as a safety net on top of its own
budget math. Profit projections (the top-left HUD and the Bazaar Macro page's status panel)
account for Hypixel's Bazaar sell tax (`Settings → Bazaar tax`, 1–1.25%) so "estimated profit"
reflects what selling now would actually pay out, not the pre-tax price.

A Buy Order is placed by searching (`/bz <item>`), clicking the matching search result, then the
on-screen action button, amount, and price-preset buttons. Clicking that price-preset button is
the one click in this whole engine that's dispatched via a real simulated mouse click
(`ClientUtils#performRealMouseClick`, routed through Minecraft's actual `mouseClicked`/
`mouseReleased`) rather than the usual slot-click shortcut - a real, reproduced failure showed
the ordinary shortcut having zero effect specifically on that screen (confirmed via diagnostic
dumps: byte-identical before/after, regardless of how long it waited first) despite working
everywhere else, and the real click fixed it. A **Sell Offer works differently**: going through
the same search-and-click path leads to a manual price-entry prompt with no preset at all - the
correct way is to click the item directly in the player's own inventory (visible at the bottom
of any open Bazaar screen, works from any single stack even if it's split across several), which
jumps straight to the same price preset for the player's *total* held quantity with no
amount-entry step at all, and works fine with the ordinary slot-click shortcut. Both flows are
confirmed end-to-end by a real completed session with real profit.

How a filled order gets claimed from the manage-orders (F6) menu remains the one genuinely
unverified piece - it lives in `flipper_coordinates.json` (in the mod's config folder) as a
placeholder `"?"` value that fails loudly instead of risking a wrong click on a screen that
spends real coins. Use `/bfm record` once claiming a real filled order and fill the resulting
coordinate in - everything else (the four item-page buttons, both price presets, and the
buy/sell claim slots) is already pre-filled from real, verified gameplay.

Running both sides concurrently is safe because a real `/bfm debug orders` dump confirmed the
manage-orders (F6) menu lays sell offers and buy orders out in separate rows (sell at `B2`, buy
at `B3`), not interleaved in one row as an earlier, never-actually-checked assumption held -
claiming or canceling always targets the correct row for whichever side it's acting on, so the
two never interfere with each other's slot.

## The macro editor (Advanced)

A named, ordered list of steps you build by hand in-game, or capture with `/bfm record`
(every command/chat message you send and every slot you click, with real elapsed time
inserted as waits - sign text isn't captured, add `SUBMIT_SIGN_TEXT` steps by hand). Step
types: `COMMAND`, `WAIT`, `WAIT_FOR_SCREEN`, `CLICK_SLOT` (coordinate like `B2`,
spreadsheet-style: column letter + row number, 9 columns per row), `COMPUTE` (evaluates a
math expression - `+ - * /`, percent literals like `25%`, `floor/ceil/round/min/max/abs`,
variables `purse`/`buyPrice`/`sellPrice`/any earlier step's result - and stores it under a
named variable, usable later as `{name}`), `SUBMIT_SIGN_TEXT`, `CLOSE_SCREEN`. The editor
also has one-click Bazaar transaction templates (Insta Buy/Sell, Buy/Sell Order) - same
verified-vs-placeholder split as the Flipper.

## Commands (development)

```bash
./gradlew build            # compile + produce the mod jar in build/libs/
./gradlew runClient        # launch a dev Minecraft client with the mod
```
