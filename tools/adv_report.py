#!/usr/bin/env python3
"""Merge tools/adv_eval.py results and pick the defense to integrate (issue #15).

Selection rule (from #15):
  eligible   clean AUC within 0.01 of no defense, real images shown HIGH <= 5%,
             clean real images abstained <= 10%, and <= 4 model runs per check;
  best       lowest WORST-CASE evasion success at eps 8/255 over every attack
             (white-box incl. EOT/adaptive, transfer, black-box), direct and after
             laundering; ties broken by framing success, then cost.
A defense that abstains (UNCERTAIN) instead of mispredicting is preferred on ties.

Usage:
    python tools/adv_report.py adv-results/ > adversarial.md
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def pct(v):
    return "n/a" if v is None else f"{v:.1%}"


def main() -> None:
    results = [json.loads(p.read_text()) for p in sorted(Path(sys.argv[1]).glob("adv__*.json"))]
    if not results:
        print("No adversarial results.")
        return
    baseline = next((r for r in results if r["defense"] == "none"), None)
    base_auc = baseline["clean"]["auc"] if baseline else None

    def worst(r, goal, eps=8.0, laundered=None):
        rows = [a for a in r["attacks"] if a["goal"] == goal and a["eps"] == eps
                and (laundered is None or a["laundered"] == laundered)]
        return max((a["success"] for a in rows), default=None)

    out = ["# Adversarial robustness benchmark (#15)\n",
           "Evasion success = AI image shown **LOW** (and not abstained). Framing success = real image shown "
           "**HIGH**. Worst case over every attack at the budget; lower is better. Images are 380×380, the "
           "app's JPEG q92 is inside the attacked pipeline; *laundered* = re-scored after JPEG q75.\n",
           "## Summary (eps 8/255)\n",
           "| Defense | Runs/check | Clean AUC | Real shown HIGH | Real abstained | Worst evasion (direct) "
           "| Worst evasion (laundered) | Worst framing | Eligible |",
           "|---|---|---|---|---|---|---|---|---|"]
    ranked = []
    for r in results:
        c = r["clean"]
        eligible = (c["real_shown_high"] <= 0.05 and c["flagged_real"] <= 0.10 and r["cost"] <= 4
                    and (base_auc is None or c["auc"] is None or c["auc"] >= base_auc - 0.01))
        wd, wl, wf = worst(r, "evasion", laundered=False), worst(r, "evasion", laundered=True), worst(r, "framing")
        out.append(f"| {r['defense']} | {r['cost']} | {c['auc']:.3f} | {pct(c['real_shown_high'])} "
                   f"| {pct(c['flagged_real'])} | {pct(wd)} | {pct(wl)} | {pct(wf)} | {'yes' if eligible else 'no'} |")
        if eligible and wd is not None:
            ranked.append((wd, wl or 0, wf or 0, r["cost"], r["defense"]))

    out.append("\n## All attacks\n")
    out.append("| Defense | Attack | eps /255 | Goal | Laundered | n | Success | Abstained | Mean P(ai) |")
    out.append("|---|---|---|---|---|---|---|---|---|")
    for r in results:
        for a in r["attacks"]:
            out.append(f"| {r['defense']} | {a['attack']} | {a['eps']:.0f} | {a['goal']} | "
                       f"{'yes' if a['laundered'] else 'no'} | {a['n']} | {pct(a['success'])} | "
                       f"{pct(a['flagged'])} | {a['mean_prob']:.3f} |")

    if ranked:
        ranked.sort()
        best = ranked[0]
        none_row = next((x for x in ranked if x[4] == "none"), None)
        verdict = (f"\n**Recommended: `{best[4]}`**: worst-case evasion {best[0]:.1%} at 8/255 "
                   f"(laundered {best[1]:.1%}, framing {best[2]:.1%}), {best[3]} runs/check.")
        if none_row and best[4] != "none":
            verdict += f" No defense: {none_row[0]:.1%}."
        if best[4] == "none":
            verdict += " No eligible defense beats the undefended model; don't add one."
        out.append(verdict)
    else:
        out.append("\n**No eligible defense** under the clean-accuracy / cost constraints.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
