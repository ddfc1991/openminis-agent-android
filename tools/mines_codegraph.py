#!/usr/bin/env python3
"""Offline, dependency-free Kotlin file/import graph for impact review.

This is a deliberately small local aid, not a semantic compiler. It reads only the
source tree passed by the caller and emits JSON; it never invokes network services.
"""
from __future__ import annotations
import argparse, json, re, subprocess
from pathlib import Path

IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.*]+)', re.M)
PACKAGE = re.compile(r'^\s*package\s+([\w.]+)', re.M)
DECL = re.compile(r'^\s*(?:public|private|internal|protected|open|data|sealed|abstract|inline|value|enum|annotation|expect|actual|override|suspend|tailrec|operator|infix|external|const|lateinit|\s)*(?:class|interface|object|fun|typealias|val|var)\s+([A-Za-z_][\w]*)', re.M)

def graph(root: Path) -> dict:
    root = root.resolve()
    files = sorted(p for p in root.rglob('*.kt') if '.gradle' not in p.parts and 'build' not in p.parts)
    package_files: dict[str, list[str]] = {}
    parsed = {}
    for path in files:
        text = path.read_text(encoding='utf-8', errors='replace')
        rel = path.relative_to(root).as_posix()
        pkg = (PACKAGE.search(text).group(1) if PACKAGE.search(text) else '')
        parsed[rel] = (text, pkg)
        package_files.setdefault(pkg, []).append(rel)
    nodes = []
    for rel, (text, pkg) in parsed.items():
        deps = set()
        for name in IMPORT.findall(text):
            wildcard = name.endswith('.*')
            head = name.removesuffix('.*')
            # A wildcard imports the package itself; a class import resolves to
            # its containing package. Keep this local and intentionally approximate.
            owner = head if wildcard else (head.rpartition('.')[0] if '.' in head else '')
            deps.update(package_files.get(owner, ()))
        deps.discard(rel)
        nodes.append({'path': rel, 'package': pkg, 'declarations': sorted(set(DECL.findall(text))), 'imports': sorted(set(IMPORT.findall(text))), 'depends_on': sorted(deps)})
    return {'schema': 'mines-codegraph.v1', 'root_name': root.name, 'parser': 'static Kotlin package/import approximation; not compiler call graph', 'files': nodes}

def changed_paths(base: str, root: Path) -> list[str]:
    result = subprocess.run(['git', '-C', str(root), 'diff', '--name-only', base, '--'], text=True, capture_output=True, check=True)
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]

def impact(g: dict, targets: list[str]) -> dict:
    files = g['files']
    by_path = {x['path']: x for x in files}
    target_set = set()
    declarations = {}
    for rel, item in by_path.items():
        for declaration in item['declarations']:
            declarations.setdefault(declaration, []).append(rel)
    for t in targets:
        p = Path(t)
        if t in by_path:
            target_set.add(t)
        elif p.name in {Path(rel).name for rel in by_path}:
            target_set.update(rel for rel in by_path if Path(rel).name == p.name)
        else:
            target_set.update(declarations.get(p.stem, ()))
    impacted = sorted(x['path'] for x in files if target_set.intersection(x['depends_on']))
    return {'targets': sorted(target_set), 'direct_importers': impacted, 'unresolved_targets': sorted(targets) if not target_set else []}

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('source_root', type=Path)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--target', action='append', default=[], help='Kotlin path, basename or declaration name; repeatable')
    ap.add_argument('--changed-from', help='Git ref to include changed paths from')
    args = ap.parse_args()
    g = graph(args.source_root)
    targets = list(args.target)
    if args.changed_from:
        targets.extend(changed_paths(args.changed_from, args.source_root))
    g['impact'] = impact(g, targets) if targets else {'targets': [], 'direct_importers': [], 'unresolved_targets': []}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(g, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Wrote offline graph for {len(g['files'])} Kotlin files to {args.out}; direct import edges are approximate.")
if __name__ == '__main__': main()
