"""Inspect user-labeled on-device samples; never treats a difference as proven detection."""
import argparse
import json
from pathlib import Path

LABELS = ('timeline', 'single_video', 'video_feed')

def compare(data):
    if data.get('schemaVersion') != 1 or not isinstance(data.get('samples'), list):
        raise ValueError('Expected an X Feed Guard schemaVersion 1 export')
    samples = data['samples']
    grouped = {label: [s for s in samples if s.get('label') == label] for label in LABELS}
    lines = ['X Feed Guard sample comparison', 'Labels were supplied by the tester; these are not detector predictions.', '']
    for label, group in grouped.items():
        lines.append(f'{label}: {len(group)} sample(s), {sum(bool(s.get("truncated")) for s in group)} truncated')
    versions = {(s.get('xVersion'), s.get('androidSdk'), s.get('device')) for s in samples}
    if len(versions) > 1:
        lines.append('WARNING: Samples mix X versions, Android versions, or devices. Compare one configuration at a time.')
    if any(not grouped[label] for label in LABELS):
        lines.append('Collect all three screen types before comparing.')
        return '\n'.join(lines)
    if any(len(group) < 3 for group in grouped.values()):
        lines.append('Collect at least three samples per screen type using different videos before drawing conclusions.')
    id_sets = {label: [{n['id'] for n in s.get('nodes', []) if n.get('id')} for s in group]
               for label, group in grouped.items()}
    stable_feed = set.intersection(*id_sets['video_feed'])
    safe_ids = set.union(*(id_sets['timeline'] + id_sets['single_video']))
    candidates = sorted(stable_feed - safe_ids)
    lines.extend(['', 'Resource IDs present in every feed sample and absent from the safe samples:'])
    lines.extend(candidates or ['None. Resource IDs alone do not establish a distinct feed screen in these samples.'])
    if any(not ids for group in id_sets.values() for ids in group):
        lines.append('Some samples have no resource IDs. Inspect class structure, bounds, and event transitions manually.')
    lines.append('Any candidates require repeated live validation, including profiles, replies, DMs, search, and notifications.')
    lines.append('This comparison cannot establish that automatic exit is safe or reliable.')
    return '\n'.join(lines)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    args = parser.parse_args()
    try:
        print(compare(json.loads(args.export.read_text(encoding='utf-8'))))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Cannot compare samples: {error}\n')
