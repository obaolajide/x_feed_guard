"""Project-local build tools. Official archives only; no system installation."""
import concurrent.futures
import argparse
import hashlib
import json
import pathlib
import subprocess
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--download', action='store_true', help='Explicitly permit official build-tool downloads')
args = parser.parse_args()
if not args.download:
    parser.exit(0, 'No downloads started. Tool setup requires an explicit --download flag after approval.\n')

ROOT = pathlib.Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
TOOLS.mkdir(exist_ok=True)
opener = urllib.request.build_opener()
opener.addheaders = [('User-Agent', 'XFeedGuard-build/0.1')]
urllib.request.install_opener(opener)

def read(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()

def parallel_tail(name, url, part, size):
    """Resume the remaining Java bytes over four bounded HTTP ranges."""
    plan_file = TOOLS / (name + '.ranges.json')
    if plan_file.exists():
        plan = json.loads(plan_file.read_text())
    else:
        prefix = part.stat().st_size if part.exists() else 0
        step = (size - prefix + 3) // 4
        plan = {'prefix': prefix, 'ranges': [[start, min(start + step, size) - 1]
                for start in range(prefix, size, step)]}
        plan_file.write_text(json.dumps(plan))
    if part.exists() and part.stat().st_size != plan['prefix']:
        raise RuntimeError('Partial Java archive changed; check saved range plan before retrying.')

    def segment(index_and_bounds):
        index, (start, end) = index_and_bounds
        target = TOOLS / f'{name}.range{index}.part'
        expected = end - start + 1
        for attempt in range(6):
            saved = target.stat().st_size if target.exists() else 0
            if saved == expected:
                return target
            if saved > expected:
                raise RuntimeError('Range exceeds expected size')
            offset = start + saved
            fragment = TOOLS / f'{name}.range{index}.fragment'
            headers = TOOLS / f'{name}.range{index}.headers'
            # Keep the HTTP transfer unbuffered in curl so interrupted transfers
            # leave every received byte available for the next bounded request.
            subprocess.run([
                'curl.exe', '--fail', '--location', '--silent', '--show-error',
                '--connect-timeout', '30', '--max-time', '600',
                '--speed-time', '60', '--speed-limit', '1024',
                '--range', f'{offset}-{end}', '--dump-header', str(headers),
                '--output', str(fragment), url,
            ], check=False)
            if fragment.exists() and fragment.stat().st_size:
                header_text = headers.read_text(errors='replace').lower()
                if f'content-range: bytes {offset}-{end}/' not in header_text:
                    raise RuntimeError('Server did not honor bounded range; fragment retained for inspection.')
                if fragment.stat().st_size > expected - saved:
                    raise RuntimeError('Server exceeded requested range')
                with target.open('ab') as output, fragment.open('rb') as source:
                    shutil.copyfileobj(source, output)
                fragment.unlink()
            print(f'Java range {index + 1}: {target.stat().st_size if target.exists() else 0}/{expected} bytes saved.', flush=True)
        if target.stat().st_size != expected:
            raise RuntimeError(f'Java range {index + 1} incomplete; saved for resume.')
        return target

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        segments = list(pool.map(segment, enumerate(plan['ranges'])))
    with part.open('ab') as output:
        for target in segments:
            with target.open('rb') as source:
                shutil.copyfileobj(source, output)
    print('Java archive ranges assembled; verifying checksum.', flush=True)

def fetch(item):
    name, url, digest, algorithm, size = item
    path = TOOLS / (name + '.zip')
    if not path.exists() or hashlib.new(algorithm, path.read_bytes()).hexdigest() != digest:
        print(f'Downloading {name}: {size / 1000000:.1f} MB', flush=True)
        part = path.with_suffix('.part')
        if name == 'jdk' and (not part.exists() or hashlib.new(algorithm, part.read_bytes()).hexdigest() != digest):
            parallel_tail(name, url, part, size)
        # Windows includes curl. Resume partial transfers instead of spending the
        # user's data downloading the same bytes again after a broken connection.
        for attempt in range(3):
            if part.exists() and hashlib.new(algorithm, part.read_bytes()).hexdigest() == digest:
                break
            transfer = subprocess.run([
                'curl.exe', '--fail', '--location', '--silent', '--show-error',
                '--connect-timeout', '30', '--max-time', '900',
                '--speed-time', '90', '--speed-limit', '1024',
                '--continue-at', '-', '--output', str(part), url,
            ], check=False)
            if transfer.returncode == 0 and hashlib.new(algorithm, part.read_bytes()).hexdigest() == digest:
                break
            print(f'Transfer incomplete for {name}; retained partial file (attempt {attempt + 1}/3).', flush=True)
        if not part.exists() or hashlib.new(algorithm, part.read_bytes()).hexdigest() != digest:
            raise RuntimeError(f'Incomplete transfer or checksum mismatch: {name}. Partial file retained.')
        part.replace(path)
        if name == 'jdk':
            for index in range(4):
                for extension in ('part', 'fragment', 'headers'):
                    (TOOLS / f'jdk.range{index}.{extension}').unlink(missing_ok=True)
            (TOOLS / 'jdk.ranges.json').unlink(missing_ok=True)
    print(f'Verified {name}', flush=True)
    return name, path

lock_path = TOOLS / 'downloads.json'
if lock_path.exists():
    items = json.loads(lock_path.read_text())
else:
    jdk = json.loads(read('https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse'))[0]['binary']['package']
    items = [('jdk', jdk['link'], jdk['checksum'], 'sha256', jdk['size'])]
    repo = ET.fromstring(read('https://dl.google.com/android/repository/repository2-1.xml'))
    for key, name in [('platforms;android-35', 'platform'), ('build-tools;35.0.0', 'build-tools'), ('platform-tools', 'platform-tools')]:
        matches = [p for p in repo if p.tag.endswith('remotePackage') and p.attrib.get('path') == key]
        package = next((p for p in matches if p.findtext('display-name') == 'Android SDK Platform 35'), matches[0])
        archive = next(a for a in package.findall('archives/archive') if a.findtext('host-os') in (None, 'windows'))
        complete = archive.find('complete')
        items.append((name, 'https://dl.google.com/android/repository/' + complete.findtext('url'), complete.findtext('checksum'), 'sha1', int(complete.findtext('size'))))
    gradle_url = 'https://services.gradle.org/distributions/gradle-8.11.1-bin.zip'
    items.append(('gradle', gradle_url, read(gradle_url + '.sha256').decode().strip(), 'sha256', 137000000))
    lock_path.write_text(json.dumps(items, indent=2))
print(f'Archives: approximately {sum(i[4] for i in items) / 1000000:.0f} MB; Gradle dependencies are additional.', flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    archives = dict(pool.map(fetch, items))
destinations = {'jdk': TOOLS / 'java', 'gradle': TOOLS / 'gradle',
                'platform': TOOLS / 'sdk/platforms/android-35',
                'build-tools': TOOLS / 'sdk/build-tools/35.0.0',
                'platform-tools': TOOLS / 'sdk/platform-tools'}
for name, destination in destinations.items():
    marker = destination / '.extracted'
    if marker.exists():
        continue
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archives[name]) as archive:
        for entry in archive.infolist():
            parts = pathlib.PurePosixPath(entry.filename).parts[1:]
            if not parts or entry.is_dir():
                continue
            target = destination.joinpath(*parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError('Unsafe archive entry')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))
    marker.touch()
(ROOT / 'local.properties').write_text('sdk.dir=' + str(TOOLS / 'sdk').replace('\\', '/') + '\n')
print('Project-local tools ready.', flush=True)
