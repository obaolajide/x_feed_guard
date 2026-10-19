"""Offline source-contract checks; not an Android compile or device test."""
import ast
from pathlib import Path
from xml.etree import ElementTree as ET
from compare_samples import compare

root = Path(__file__).resolve().parents[1]
for path in (root / 'app').rglob('*.xml'):
    ET.parse(path)
for path in (root / 'scripts').glob('*.py'):
    ast.parse(path.read_text(encoding='utf-8'))

manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
ns = '{http://schemas.android.com/apk/res/android}'
assert {p.attrib[ns + 'name'] for p in manifest.findall('uses-permission')} == {
    'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
    'android.permission.POST_NOTIFICATIONS', 'android.permission.RECEIVE_BOOT_COMPLETED'
}, 'Unexpected application permission'
support = manifest.find("application/service[@" + ns + "name='.ProtectionService']")
assert support.attrib[ns + 'exported'] == 'false'
assert support.attrib[ns + 'foregroundServiceType'] == 'specialUse'
assert support.attrib[ns + 'stopWithTask'] == 'false'
config = ET.parse(root / 'app/src/main/res/xml/observation_service.xml').getroot()
assert config.attrib[ns + 'packageNames'] == 'com.twitter.android'
assert config.attrib.get(ns + 'canPerformGestures') is None
assert 'flagRequestAccessibilityButton' in config.attrib[ns + 'accessibilityFlags']
main_activity = manifest.find('application/activity')
assert main_activity.attrib[ns + 'launchMode'] == 'singleTop'
source = (root / 'app/src/main/java/app/xfeedguard/ObservationService.kt').read_text(encoding='utf-8')
for forbidden in ('dispatchGesture(', '.contentDescription', '.text', 'takeScreenshot('):
    assert forbidden not in source, f'Unexpected sensitive API: {forbidden}'
assert source.count('performGlobalAction(') == 1
assert 'performGlobalAction(GLOBAL_ACTION_BACK)' in source
# Regression: Android service callbacks must not silently clear the user's choice.
assert 'store.blocking =' not in source
activity = (root / 'app/src/main/java/app/xfeedguard/MainActivity.kt').read_text(encoding='utf-8')
refresh = activity.split('private fun refresh()', 1)[1].split('private fun explainAccess()', 1)[0]
assert 'store.blocking =' not in refresh, 'Status refresh must not mutate blocking preference'
assert 'registerAccessibilityButtonCallback(shortcutCallback, handler)' in source
assert 'ArrayBlockingQueue<Runnable>(4)' in source
assert 'AccessibilityEvent.obtain(event)' in source

def sample(label, ids):
    return dict(label=label, nodes=[{'id': value} for value in ids],
                xVersion='fixture', androidSdk=33, device='fixture')

assert 'Collect all three' in compare({'schemaVersion': 1, 'samples': []})
data = {'schemaVersion': 1, 'samples': [sample(label, ['fixture/shared'])
                                     for label in ('timeline', 'single_video', 'video_feed')]}
assert 'None. Resource IDs alone' in compare(data)
data['samples'][2]['nodes'].append({'id': 'fixture/feed_candidate'})
assert '\nfixture/feed_candidate\n' in compare(data)
data['samples'][1]['nodes'].append({'id': 'fixture/feed_candidate'})
assert 'None. Resource IDs alone' in compare(data)
print('PASS: XML, script syntax, privacy/API constraints, and four comparison cases.')
print('This checker does not run Kotlin compilation, Android lint, UI, or device tests.')
