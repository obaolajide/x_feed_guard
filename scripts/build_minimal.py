"""Build this dependency-light Android app entirely offline from verified local tools.

Uses the Kotlin 2.0.20 compiler and runtime shipped in Gradle 8.11.1, plus SDK
Build Tools 35.0.0. Does not execute Gradle or download any dependency.
"""
import hashlib
import os
import shutil
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
BUILD = ROOT / 'app/build/minimal'
DIST = ROOT / 'dist'
JAVA_HOME = TOOLS / 'java'
JAVA = JAVA_HOME / 'bin/java.exe'
SDK = TOOLS / 'sdk'
BT = SDK / 'build-tools/35.0.0'
LIB = TOOLS / 'gradle/lib'
ANDROID_JAR = SDK / 'platforms/android-35/android.jar'
STDLIB = LIB / 'kotlin-stdlib-2.0.20.jar'
ANNOTATIONS = LIB / 'annotations-24.0.1.jar'
PACKAGE = 'app.xfeedguard'
APK = DIST / 'x-feed-guard-0.7.0-debug.apk'

for required in (JAVA, ANDROID_JAR, STDLIB, ANNOTATIONS,
                 LIB / 'kotlin-compiler-embeddable-2.0.20.jar', BT / 'aapt2.exe', BT / 'lib/d8.jar'):
    if not required.is_file():
        raise SystemExit(f'Missing local build tool: {required}. No downloads attempted.')

if not BUILD.resolve().is_relative_to(ROOT.resolve()):
    raise SystemExit('Build output must stay inside this project.')
for folder in ('generated', 'classes', 'dex', 'tests'):
    directory = BUILD / folder
    if directory.exists():
        if not directory.resolve().is_relative_to(BUILD.resolve()):
            raise SystemExit('Refusing to clean a build folder outside the output directory.')
        shutil.rmtree(directory)
for directory in (BUILD, DIST, BUILD / 'generated', BUILD / 'classes', BUILD / 'dex', BUILD / 'tests', TOOLS / 'tmp', TOOLS / 'android-user'):
    directory.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
env['JAVA_HOME'] = str(JAVA_HOME)
env['ANDROID_USER_HOME'] = str(TOOLS / 'android-user')
env['TEMP'] = env['TMP'] = str(TOOLS / 'tmp')
env['JAVA_TOOL_OPTIONS'] = f'-Djdk.net.unixdomain.tmpdir={TOOLS / "tmp"}'

def run(label, *args):
    print(label, flush=True)
    subprocess.run([str(arg) for arg in args], cwd=ROOT, env=env, check=True)

manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
manifest.getroot().set('package', PACKAGE)
manifest.find('application').set('{http://schemas.android.com/apk/res/android}debuggable', 'true')
manifest_path = BUILD / 'AndroidManifest.xml'
manifest.write(manifest_path, encoding='utf-8', xml_declaration=True)

run('1/7 Compile Android resources', BT / 'aapt2.exe', 'compile', '--dir',
    ROOT / 'app/src/main/res', '-o', BUILD / 'resources.zip')
run('2/7 Link resources and generate R.java', BT / 'aapt2.exe', 'link',
    '-I', ANDROID_JAR, '--manifest', manifest_path, '--java', BUILD / 'generated',
    '--min-sdk-version', '26', '--target-sdk-version', '35',
    '--version-code', '9', '--version-name', '0.7.0',
    '-o', BUILD / 'resources.apk', BUILD / 'resources.zip')

java_sources = sorted((BUILD / 'generated').rglob('*.java'))
run('3/7 Compile generated resource classes', JAVA_HOME / 'bin/javac.exe', '--release', '8',
    '-classpath', ANDROID_JAR, '-d', BUILD / 'classes', *java_sources)
kotlin_sources = sorted((ROOT / 'app/src/main/java').rglob('*.kt'))
run('Compile trace policy tests offline', JAVA, '-Xmx512m', '-cp', str(LIB / '*'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '1.8',
    '-classpath', os.pathsep.join(str(p) for p in (STDLIB, ANNOTATIONS)), '-d', BUILD / 'tests',
    ROOT / 'app/src/main/java/app/xfeedguard/TransitionPlan.kt',
    ROOT / 'app/src/main/java/app/xfeedguard/SwipePolicy.kt',
    ROOT / 'app/src/main/java/app/xfeedguard/ProtectionStatus.kt',
    ROOT / 'tests/TransitionPlanTest.kt', ROOT / 'tests/SwipePolicyTest.kt', ROOT / 'tests/ProtectionStatusTest.kt')
run('Test trace timing and retention limits', JAVA, '-cp',
    os.pathsep.join(str(p) for p in (BUILD / 'tests', STDLIB)), 'app.xfeedguard.TransitionPlanTestKt')
classpath = os.pathsep.join(str(path) for path in (ANDROID_JAR, STDLIB, ANNOTATIONS, BUILD / 'classes'))
run('4/7 Compile Kotlin source offline', JAVA, '-Xmx1024m', '-cp', str(LIB / '*'),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
    '-jvm-target', '1.8', '-classpath', classpath, '-d', BUILD / 'classes', *kotlin_sources)
run('Package compiled classes', JAVA_HOME / 'bin/jar.exe', 'cf', BUILD / 'classes.jar', '-C', BUILD / 'classes', '.')
run('5/7 Convert classes and Kotlin runtime to DEX', JAVA, '-Xmx1024m', '-cp', BT / 'lib/d8.jar',
    'com.android.tools.r8.D8', '--min-api', '26', '--lib', ANDROID_JAR,
    '--output', BUILD / 'dex', BUILD / 'classes.jar', STDLIB)

unsigned = BUILD / 'unsigned.apk'
unsigned.write_bytes((BUILD / 'resources.apk').read_bytes())
with ZipFile(unsigned, 'a', compression=ZIP_DEFLATED) as archive:
    for dex in sorted((BUILD / 'dex').glob('*.dex')):
        archive.write(dex, dex.name)
run('6/7 Align APK', BT / 'zipalign.exe', '-f', '4', unsigned, BUILD / 'aligned.apk')

key = TOOLS / 'android-user/debug.keystore'
if not key.exists():
    run('Create local debug signing key', JAVA_HOME / 'bin/keytool.exe', '-genkeypair',
        '-keystore', key, '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
        '-dname', 'CN=Android Debug,O=Android,C=US')
run('7/7 Sign APK', JAVA, '-jar', BT / 'lib/apksigner.jar', 'sign', '--ks', key,
    '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', APK, BUILD / 'aligned.apk')
run('Verify APK signature', JAVA, '-jar', BT / 'lib/apksigner.jar', 'verify', '--verbose', APK)
run('Verify APK alignment', BT / 'zipalign.exe', '-c', '4', APK)
digest = hashlib.sha256(APK.read_bytes()).hexdigest()
APK.with_suffix('.apk.sha256').write_text(f'{digest}  {APK.name}\n')
print(f'APK ready: {APK}\nSize: {APK.stat().st_size:,} bytes\nSHA256: {digest}', flush=True)
