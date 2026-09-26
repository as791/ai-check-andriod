#!/usr/bin/env python3
"""Merge tools/adv_eval.py results and pick the defense to integrate (issue #15).

Selection rule (from #15), applied per detector model (`--model` of adv_eval.py):
  eligible   clean AUC within 0.01 of the same model with no defense, real images shown
             HIGH <= 5%, clean real images abstained <= 10%, and <= --max-cost model
             runs per check (default 6: the consistency check on the 3-run ensemble);
  best       lowest WORST-CASE evasion success at eps 8/255 over every attack
             (white-box incl. EOT/adaptive, transfer, black-box), direct and after
             laundering; ties broken by framing success, then cost.
A defense that abstains (UNCERTAIN) instead of mispredicting is preferred on ties.

Usage:
    python tools/adv_report.py adv-results/ [--max-cost 6] > adversarial.md
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def pct(v):
    return "n/a" if v is None else f"{v:.1%}"


def worst(r, goal, eps=8.0, laundered=None):
    rows = [a for a in r["attacks"] if a["goal"] == goal and a["eps"] == eps
            and (laundered is None or a["laundered"] == laundered)]
    return max((a["success"] for a in rows), default=None)


def report_model(model: str, results: list[dict], max_cost: int) -> list[str]:
    baseline = next((r for r in results if r["defense"] == "none"), None)
    base_auc = baseline["clean"]["auc"] if baseline else None
    surrogate = next((r.get("surrogate") for r in results if r.get("surrogate")), "n/a")
    out = [f"## Model: `{model}` (eps 8/255; transfer surrogate: {surrogate})\n",
           "| Defense | Runs/check | n clean | Clean AUC | Real shown HIGH | AI shown LOW | Real abstained "
           "| Worst evasion (direct) | Worst evasion (laundered) | Worst framing | Eligible |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    ranked = []
    for r in results:
        c = r["clean"]
        eligible = (c["real_shown_high"] <= 0.05 and c["flagged_real"] <= 0.10 and r["cost"] <= max_cost
                    and (base_auc is None or c["auc"] is None or c["auc"] >= base_auc - 0.01))
        wd, wl, wf = worst(r, "evasion", laundered=False), worst(r, "evasion", laundered=True), worst(r, "framing")
        out.append(f"| {r['defense']} | {r['cost']} | {c['n']} | {c['auc']:.3f} | {pct(c['real_shown_high'])} "
                   f"| {pct(c.get('ai_shown_low'))} | {pct(c['flagged_real'])} | {pct(wd)} | {pct(wl)} | {pct(wf)} "
                   f"| {'yes' if eligible else 'no'} |")
        if eligible and wd is not None:
            ranked.append((wd, wl or 0, wf or 0, r["cost"], r["defense"]))

    if ranked:
        ranked.sort()
        best = ranked[0]
        none_row = next((x for x in ranked if x[4] == "none"), None)
        verdict = (f"\n**Recommended for `{model}`: `{best[4]}`**: worst-case evasion {best[0]:.1%} at 8/255 "
                   f"(laundered {best[1]:.1%}, framing {best[2]:.1%}), {best[3]} runs/check.")
        if none_row and best[4] != "none":
            verdict += (f" No defense: evasion {none_row[0]:.1%} (laundered {none_row[1]:.1%}), "
                        f"framing {none_row[2]:.1%}.")
        if best[4] == "none":
            verdict += " No eligible defense beats the undefended model; don't add one."
        out.append(verdict)
    else:
        out.append(f"\n**No eligible defense for `{model}`** under the clean-accuracy / cost constraints.")
    return out


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("results", type=Path)
    parser.add_argument("--max-cost", type=int, default=6)
    args = parser.parse_args()

    results = [json.loads(p.read_text()) for p in sorted(args.results.glob("adv__*.json"))]
    if not results:
        print("No adversarial results.")
        return
    by_model: dict[str, list[dict]] = {}
    for r in results:
        by_model.setdefault(r.get("model", "bundled"), []).append(r)

    out = ["# Adversarial robustness benchmark (#15)\n",
           "Evasion success = AI image shown **LOW** (and not abstained). Framing success = real image shown "
           "**HIGH**. Worst case over every attack at the budget; lower is better. Images are 380×380, the "
           "app's JPEG q92 is inside the attacked pipeline; *laundered* = re-scored after JPEG q75.\n"]
    for model, rows in by_model.items():
        out += report_model(model, rows, args.max_cost)
        out.append("")

    out.append("## All attacks\n")
    out.append("| Model | Defense | Attack | eps /255 | Goal | Laundered | n | Success | Abstained | Mean P(ai) |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        for a in r["attacks"]:
            out.append(f"| {r.get('model', 'bundled')} | {r['defense']} | {a['attack']} | {a['eps']:.0f} | {a['goal']} | "
                       f"{'yes' if a['laundered'] else 'no'} | {a['n']} | {pct(a['success'])} | "
                       f"{pct(a['flagged'])} | {a['mean_prob']:.3f} |")
    print("\n".join(out))


if __name__ == "__main__":
    main()
