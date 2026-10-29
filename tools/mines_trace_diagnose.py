#!/usr/bin/env python3
"""Privacy-preserving, offline aggregate diagnostics for opt-in JSONL traces."""
from __future__ import annotations
import argparse, collections, json, math, re
from pathlib import Path

def summarize(path: Path) -> dict:
    counts = collections.Counter(); duration = collections.Counter(); failures = collections.Counter()
    malformed = 0
    with path.open(encoding='utf-8') as f:
        for line in f:
            if not line.strip(): continue
            try: row = json.loads(line)
            except (json.JSONDecodeError, TypeError): malformed += 1; continue
            if not isinstance(row, dict): malformed += 1; continue
            # Only aggregate allowlisted fields; never copy free-form arguments/results.
            raw_tool = row.get('tool') if isinstance(row.get('tool'), str) else ''
            # Identifiers only: never echo arbitrary tool labels that could embed a token.
            tool = raw_tool[:80] if re.fullmatch(r'[A-Za-z0-9_.:-]{1,80}', raw_tool) else 'other'
            raw_outcome = row.get('outcome') if isinstance(row.get('outcome'), str) else ''
            outcome = raw_outcome.lower() if raw_outcome.lower() in ('ok', 'success', 'completed', 'failed', 'error', 'timeout', 'cancelled') else 'unknown'
            ms = row.get('duration_ms')
            ms = ms if isinstance(ms, (int, float)) and not isinstance(ms, bool) and math.isfinite(ms) and 0 <= ms <= 86_400_000 else 0
            counts[tool] += 1; duration[tool] += ms
            if outcome not in ('ok', 'success', 'completed'): failures[tool] += 1
    return {'schema':'mines-trace-summary.v1','records':sum(counts.values()),'malformed_lines':malformed,
            'tools':[{'tool':k,'calls':v,'avg_duration_ms':round(duration[k]/v,1),'non_success':failures[k]} for k,v in sorted(counts.items())]}

def main():
    ap=argparse.ArgumentParser(description=__doc__); ap.add_argument('input',type=Path); ap.add_argument('--out',type=Path)
    a=ap.parse_args(); result=summarize(a.input); text=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if a.out: a.out.write_text(text,encoding='utf-8')
    else: print(text,end='')
if __name__=='__main__':main()
