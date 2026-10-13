# Economy / Bot Rental

Players can pay real economy currency to rent a bot for a fixed number of hours - the bot
auto-despawns the moment its paid time runs out. Disabled by default.

## Overview

- **Provider-agnostic.** FPP talks to whichever economy plugin is actually installed:
  [Vault](https://github.com/milkbowl/Vault), [ExcellentEconomy](https://nightexpressdev.com/excellenteconomy/),
  or a Vault-compatible reimplementation like [Vault2.0](https://github.com/shalom25/Vault2.0) - see
  [Supported Economy Plugins](#supported-economy-plugins) below for how the detection actually works.
- **Billed per hour.** `/fpp rent buy <hours>` spawns a new rented bot; `/fpp rent extend <bot> <hours>`
  adds more time to one you already have.
- **Dynamic - works with your own shop plugin too.** `/fpp rent give <player> <bot|--new> <hours>`
  grants rental time **without touching any economy plugin at all**. Point your own shop GUI
  (ShopGUIPlus, EconomyShopGUI, zShop, …) at this as a console reward command and it charges the
  player however *it* likes - FPP's own economy integration is entirely optional for this path.
- **Never permanently loses a bot's data.** A rented bot despawning on expiry goes through the exact
  same despawn/inventory-save path as `/fpp despawn` - nothing special or destructive happens.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/fpp rent [gui]` | Open the rent shop GUI | `fpp.rent` / `fpp.rent.info` |
| `/fpp rent packages` | List configured packages with your price | `fpp.rent` |
| `/fpp rent package <id> [bot] [--for <player>]` | Buy a package (new bot, or extend `bot`); `--for` charges another online player - shop-plugin entry point | `fpp.rent` / `fpp.rent.give` |
| `/fpp rent buy <hours>` | Spawn a new rented bot for `<hours>` hours, charging your balance | `fpp.rent` |
| `/fpp rent extend <bot> <hours>` | Add `<hours>` more hours to a rented bot you own, charging your balance | `fpp.rent` |
| `/fpp rent quote <hours> [--new]` | Show exactly what a purchase would cost you (slot fee, hourly rate, discount) | `fpp.rent` |
| `/fpp rent info [bot]` | Show remaining time (all your rented bots, or one) | `fpp.rent.info` |
| `/fpp rent auto <bot> <on [hours]\|off>` | Renew a rented bot automatically shortly before it expires | `fpp.rent.auto` |
| `/fpp rent list [player]` | List your rentals (admins: every rental on the server, or one player's) | `fpp.rent.info` / `fpp.rent.admin` |
| `/fpp rent give <player> <bot\|--new> <hours>` | Grant hours without charging - admin/console/shop-plugin entry point | `fpp.rent.give` |
| `/fpp rent clear <bot>` | Remove a bot's rental - it becomes permanent again | `fpp.rent.give` |

`fpp.rent`, `fpp.rent.info` and `fpp.rent.auto` are included in `fpp.use` (the default everyone-tier
permission) - any player can attempt a purchase, gated purely by actually having the money and
`economy.enabled: true`.

## Rent GUI

`/fpp rent` (or `/fpp rent gui`, or the **rent a bot** card in `/fpp menu`) opens the built-in shop:

- **Shop** - every package from `economy.rental.packages`, priced for *you* (slot fee, hourly rate, bulk and
  permission discounts applied). Click once to arm, click again to confirm.
- **My rentals** - your rented bots with time left and auto-renew state. Click one to manage it.
- **Bot page** - the same packages as extensions, plus the **auto-renew** switch.
- The account card shows your balance, rented-bot count/limit and discount. Disable with
  `economy.rental.gui.enabled: false` (the command then prints usage instead).

## Using other shop GUIs

Any GUI plugin (DeluxeMenus, ChestCommands, ShopGUIPlus, EconomyShopGUI, TrMenu, a Skript menu, …) can sell
rentals - FPP exposes four neutral integration points, none tied to a specific plugin:

1. **Package command** - define offers once under `economy.rental.packages`, then point a menu item at
   `fpp rent package <id>` (run as the player) or `fpp rent package <id> --for %player_name%` (run as
   console, needs `fpp.rent.give`). Add a bot name to extend instead of renting a new bot:
   `fpp rent package day MyBot`. FPP charges through your economy and applies discounts/limits itself.
2. **Let the shop plugin charge** - use `fpp rent give %player% --new 24` as the reward command (see below).
3. **Placeholders** for menu item text (PlaceholderAPI): `%fpp_rent_package_<id>_price%`, `_hours`, `_label`,
   `_discount`; `%fpp_rent_price_<hours>%` (extend), `%fpp_rent_price_new_<hours>%`; `%fpp_rent_balance%`,
   `%fpp_rent_discount%`, `%fpp_rent_limit%`, `%fpp_rent_slots_left%`, `%fpp_rental_autorenew%`.
4. **Event for plugins** - `FppRentalPurchaseEvent` fires before every charge (`command`, `gui`, `package`)
   with the buyer, type (`NEW`/`EXTEND`), bot, hours, price and source. Cancel it or `setPrice()` to apply
   your own sale, currency check or surcharge.

`/fpp rent packages` lists the offers with your personal price.

## Pricing

One price engine ([`RentalPricing`]) quotes and charges everywhere (command, GUI, auto-renew):

```
total = (slot fee [new bots only] + price-per-hour × hours) × (1 − discount%)
discount% = max(bulk tier reached, fpp.rent.discount.<n>)      # they do not stack
```

- **Bulk tiers** - `economy.rental.bulk-discounts: {6: 5, 24: 15, 72: 25}`: rent at least *hours* for *percent* off.
- **Permission discounts** - `fpp.rent.discount.<1-100>` (e.g. `/lp group vip permission set fpp.rent.discount.25 true`); the highest node held applies.
- **Free** - `fpp.rent.free` pays nothing (and needs no economy plugin), but still counts toward the rented-bot limit.
- Prices are rounded to cents; a zero total never contacts the economy backend.

Use `/fpp rent quote 24 --new` to preview a purchase.

## Limits & permissions

| Node | Effect | Default |
|------|--------|---------|
| `fpp.rent` | buy / extend / quote | everyone |
| `fpp.rent.info` | `info`, own `list` | everyone |
| `fpp.rent.auto` | `auto` renew | everyone |
| `fpp.rent.give` | `give` / `clear` (shop-plugin entry point) | op |
| `fpp.rent.admin` | `list` everything, act on others' rentals | op |
| `fpp.rent.free` | no charge | op |
| `fpp.rent.unlimited` | rentals never expire / ignore the cap | op |
| `fpp.rent.discount.<n>` | n% off (best wins) | none |
| `fpp.rent.limit.<n>` | max concurrently rented bots (overrides `max-bots-per-player`) | none |
| `fpp.spawn.free` | waive `economy.spawn.price` | op |

## Auto-renew

`/fpp rent auto <bot> on [hours]` makes the bot renew `economy.rental.auto-renew-lead-minutes`
(default 2) before it expires, charging the owner (works while they are offline with Vault) at their
normal price including discounts. If they cannot pay, auto-renew is switched off and the bot expires
normally; the owner is told either way. The choice is stored in the database and survives restarts.

## Expiry grace & spawn price

- `economy.rental.expiry-grace-seconds` keeps an expired bot for a short window so its owner can still extend.
- `economy.spawn.price` charges user-tier `/fpp spawn` (also per bot with `--count`); failed spawns are refunded
  automatically. Admins and `fpp.spawn.free` never pay.
- `economy.log-transactions: true` logs every charge/refund at INFO for an audit trail.

## Setup

```yaml
# plugins/FakePlayerPlugin/config.yml
economy:
  enabled: false          # turn on to allow real purchases via /fpp rent buy/extend
  provider: auto          # auto | vault | excellenteconomy | none
  excellent-economy-currency-id: money   # only used when the resolved provider is ExcellentEconomy
  rental:
    price-per-hour: 100.0
    price-per-bot-slot: 0.0     # one-time extra charge for a brand-new rented bot
    min-hours: 1
    max-hours: 72
    max-banked-hours: 168       # hard cap on time a bot can have banked across extensions
    warn-minutes-before-expiry: 10
    sweep-interval-seconds: 30
    max-bots-per-player: 3      # rented bots only; fpp.rent.limit.<n> overrides, fpp.rent.unlimited bypasses
    expiry-grace-seconds: 0
    bulk-discounts: {6: 5, 24: 15, 72: 25}
    auto-renew-lead-minutes: 2
    auto-renew-hours: 1
  spawn:
    price: 0.0                  # user-tier /fpp spawn price
  log-transactions: false
```

`economy.enabled: false` (the default) only disables the self-service `buy`/`extend` purchase path -
`/fpp rent give` still works with no economy plugin involved at all, since it never touches money.

## Supported Economy Plugins

FPP resolves a working economy backend in this order (`economy.provider: auto`, the default):

1. **Vault's `Economy` service**, via Bukkit's own `ServicesManager` - the same lookup virtually every
   economy-integrated plugin uses. This one integration transparently covers:
   - **Real [Vault](https://github.com/milkbowl/Vault)** with any Vault-compatible economy plugin
     (EssentialsX, CMI, …) behind it.
   - **[Vault2.0](https://github.com/shalom25/Vault2.0)** - inspected directly: it registers itself
     under the literal plugin name `"Vault"` and its own economy class implements the exact same
     `net.milkbowl.vault.economy.Economy` interface real Vault does. There's nothing to distinguish
     it from real Vault at the API level, so it's covered automatically with zero extra code.
   - **[ExcellentEconomy](https://nightexpressdev.com/excellenteconomy/)** *when Vault is also
     installed* - its own documentation states it "works right out of the box with Vault."
2. **ExcellentEconomy's native API** (`su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI`, also
   via `ServicesManager`) - used when ExcellentEconomy is installed **without** Vault present.
   ExcellentEconomy is a multi-currency plugin with no single fixed default currency, so
   `economy.excellent-economy-currency-id` must name a currency you've actually created in it.
3. Otherwise, purchasing is unavailable (`/fpp rent buy`/`extend` report "no economy plugin
   available") - `/fpp rent give` is unaffected.

Set `economy.provider` to `vault` or `excellenteconomy` to pin one specific backend instead of
auto-detecting.

All of this is soft-dependency reflection under the hood (`Vault`/`ExcellentEconomy` are optional -
FPP works fine with neither installed), the same defensive style used for every other optional
integration in this plugin.

## Custom Shop Plugin Integration

Since `/fpp rent give` never touches an economy plugin itself, it's the integration point for
building your own shop with a completely different plugin (a GUI shop, a `/buy` command shop, a
crate/key system, whatever you already use). Set its reward/command action to:

```
fpp rent give %player% --new 4
```

or, to add time to a specific existing bot instead of spawning a new one:

```
fpp rent give %player% <bot-name> 4
```

Run this as console (`fpp.rent.give` defaults to `op`); most shop plugins support exactly this
"execute console command as reward" pattern. FPP charges nothing itself here - your shop plugin's own
economy hook (which might not even be Vault) handles the payment; this command only applies the
resulting bot/time.

## Notification & Expiry

- A rented bot's owner gets a one-time warning message `economy.rental.warn-minutes-before-expiry`
  minutes before time runs out (10 by default; set to `0` to disable).
- When time actually reaches zero, the bot despawns (same safe despawn path as `/fpp despawn`) and the
  owner is notified if online.
- `fpp.rent.unlimited` makes a player's rented bots never expire from time running out - useful for
  staff/VIP bots that should never be affected by the rental system at all.
- Rental time survives server restarts (persisted the same way every other bot setting is, to YAML
  and the database).

## Settings GUI

Each bot's settings GUI (`⚙ ɢᴇɴᴇʀᴀʟ` category) has a **ʀᴇɴᴛᴀʟ ᴛɪᴍᴇ** tile showing remaining time (or
`ᴘᴇʀᴍᴀɴᴇɴᴛ` for a non-rented bot) - click it to buy more hours the same way `/fpp rent extend` does.

## Placeholders

See [Placeholders](Placeholders) - `%fpp_rental_count%` and `%fpp_rental_remaining%`.
