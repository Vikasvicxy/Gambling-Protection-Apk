"""Generates docs/blocklist/blocklist.json and a matching manifest.json.

The payload is a flat, sorted, de-duplicated domain list so the release tooling and
the on-device compiler can both consume it without a merge step. Sorting is not
cosmetic: the file is hashed, and a stable byte order keeps the SHA-256 pinned in
the manifest reproducible across runs and across machines.
"""

import hashlib
import json
import os
import time

CATEGORIES = {
    "CASINO": ["HIGH", "CRITICAL"],
    "SPORTSBOOK": ["HIGH", "CRITICAL"],
    "POKER": ["HIGH", "HIGH"],
    "LOTTERY": ["MEDIUM", "MEDIUM"],
    "BINGO": ["MEDIUM", "MEDIUM"],
    "CRYPTO_GAMBLING": ["HIGH", "CRITICAL"],
    "AFFILIATE": ["MEDIUM", "MEDIUM"],
    "SOCIAL_CASINO": ["MEDIUM", "HIGH"],
}

DOMAINS = {
    "CASINO": [
        "bet365.com", "betway.com", "casumo.com", "williamhill.com", "unibet.com",
        "888casino.com", "casino.com", "leovegas.com", "pokerstars.com", "partypoker.com",
        "bovada.lv", "bovada.com", "fanduel.com", "draftkings.com", "betfair.com",
        "ladbrokes.com", "paddypower.com", "skybet.com", "nolvada.com", "sbgobet.com",
        "jackpotjoy.com", "casino777.com", "mrgreen.com", "betvictor.com", "10bet.com",
        "intercasino.com", "coinbase-gamble.invalid", "stake.com", "roobet.com", "bc.game",
    ],
    "SPORTSBOOK": [
        "draftkings.com", "fanduel.com", "betmgm.com", "caesars.com", "pointsbet.com",
        "betrivers.com", "barstool.com", "espn.com", "sbgobet.com", "bovada.lv",
        "unibet.com", "pinnacle.com", "marathonbet.com", "mybookie.ag", "cloudbet.com",
    ],
    "POKER": [
        "pokerstars.com", "partypoker.com", "ggpoker.com", "pokerdomes.com", "coinpoker.com",
        "betonline.ag", "wsop.com", "pokerstars.net", "partypoker.com", "natural8.com",
    ],
    "LOTTERY": [
        "lottery.com", "lotto.co.uk", "national-lottery.co.uk", "euromillions.com",
        "lottoland.com", "thelott.com", "lottery.net", "playlottery.com",
    ],
    "BINGO": [
        "bingo.com", "bongbingo.com", "bingo.com.au", "mr-bingo.com", "yayabingo.com",
        "bob's-bingo.invalid",
    ],
    "CRYPTO_GAMBLING": [
        "stake.com", "roobet.com", "bc.game", "rollbit.com", "cloudbet.com",
        "betwinner.com", "vave.com", "metawin.com", "p2pbet.com", "casinofar.com",
    ],
    "AFFILIATE": [
        "oddschecker.com", "oddsportal.com", "betexplorer.com", "soccerway.com",
        "gambling.com", "askgamblers.com", "casino.guru", "gambler.com",
    ],
    "SOCIAL_CASINO": [
        "slotzilla.com", "popslots.com", "chumba-casino.invalid", "jackpotparty.com",
        "solitaire.com", "doubleup.com", "mytribe.invalid",
    ],
}

VERSION = 20260404
RELEASED_AT_MS = int(os.environ.get("SHIELD_BLOCKLIST_RELEASED_AT_MS", time.time() * 1000))


def main() -> None:
    entries = {}
    for category, domains in DOMAINS.items():
        confidence, risk = CATEGORIES[category]
        for domain in domains:
            entries.setdefault(
                domain,
                {
                    "domain": domain,
                    "category": category,
                    "confidence": confidence,
                    "riskLevel": risk,
                    "status": "ACTIVE",
                    "source": "gamblock-cdn",
                    "appliesToSubdomains": True,
                },
            )

    payload = {
        "schema": "gamblock-release-blocklist",
        "version": VERSION,
        "releasedAtEpochMs": RELEASED_AT_MS,
        "entries": [entries[d] for d in sorted(entries)],
    }

    # Canonical bytes: sorted keys, no insignificant whitespace. This is what gets
    # hashed and what must be what actually ships.
    body = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    digest = hashlib.sha256(body).hexdigest()

    out_dir = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(out_dir, "blocklist.json"), "wb") as handle:
        handle.write(body)

    manifest = {
        "schema": "gamblock-release-manifest-preview",
        "version": VERSION,
        "releaseId": "blocklist-{0}".format(VERSION),
        "channel": "STABLE",
        "releasedAtEpochMs": RELEASED_AT_MS,
        "full": {"fileName": "blocklist.json", "sha256": digest, "sizeBytes": len(body)},
        "delta": None,
        "rollback": False,
    }
    with open(os.path.join(out_dir, "manifest.json"), "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2, sort_keys=True)
        handle.write("\n")

    print("  entries={0} bytes={1}".format(len(payload["entries"]), len(body)))
    print("  sha256={0}".format(digest))


if __name__ == "__main__":
    main()