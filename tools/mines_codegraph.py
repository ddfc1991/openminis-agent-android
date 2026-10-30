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
    """Return tracked changes relative to the scanned source root, not the repo root."""
    root = root.resolve()
    repo = subprocess.run(
        ['git', '-C', str(root), 'rev-parse', '--show-toplevel'],
        text=True, capture_output=True, check=True,
    )
    repo_root = Path(repo.stdout.strip()).resolve()
    prefix = root.relative_to(repo_root).as_posix()
    command = ['git', '-C', str(repo_root), 'diff', '--name-only', '-z', base, '--']
    if prefix not in ('', '.'):
        command.append(prefix)
    result = subprocess.run(command, text=True, capture_output=True, check=True)
    paths = [path for path in result.stdout.split('\0') if path]
    if prefix not in ('', '.'):
        marker = prefix.rstrip('/') + '/'
        paths = [path[len(marker):] for path in paths if path.startswith(marker)]
    return paths

def impact(g: dict, targets: list[str]) -> dict:
    files = g['files']
    by_path = {x['path']: x for x in files}
    basenames: dict[str, list[str]] = {}
    declarations: dict[str, list[str]] = {}
    for rel, item in by_path.items():
        basenames.setdefault(Path(rel).name, []).append(rel)
        for declaration in item['declarations']:
            declarations.setdefault(declaration, []).append(rel)

    target_set = set()
    unresolved = []
    for target in targets:
        path = Path(target)
        if target in by_path:
            matches = [target]
        elif path.name in basenames:
            matches = basenames[path.name]
        else:
            matches = declarations.get(path.stem, [])
        if matches:
            target_set.update(matches)
        else:
            unresolved.append(target)

    reverse: dict[str, set[str]] = {}
    for item in files:
        for dependency in item.get('depends_on', []):
            reverse.setdefault(dependency, set()).add(item['path'])

    direct = set()
    indirect = set()
    visited = set(target_set)
    frontier = set(target_set)
    depth = 0
    while frontier:
        next_frontier = set().union(*(reverse.get(path, set()) for path in frontier)) - visited
        if not next_frontier:
            break
        depth += 1
        if depth == 1:
            direct = next_frontier
        else:
            indirect.update(next_frontier)
        visited.update(next_frontier)
        frontier = next_frontier

    return {
        'targets': sorted(target_set),
        'direct_importers': sorted(direct),
        'indirect_importers': sorted(indirect),
        'unresolved_targets': sorted(set(unresolved)),
    }

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
    g['impact'] = impact(g, targets) if targets else {'targets': [], 'direct_importers': [], 'indirect_importers': [], 'unresolved_targets': []}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(g, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Wrote offline graph for {len(g['files'])} Kotlin files to {args.out}; direct import edges are approximate.")
if __name__ == '__main__': main()
