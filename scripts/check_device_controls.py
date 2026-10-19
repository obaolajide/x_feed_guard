"""Verify only X Feed Guard's visible controls over an authorized USB connection.

No downloads, permission changes, X interaction, or private-screen screenshots.
--test-switch exercises both switch states, ending at its initial setting.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / '.tools/sdk/platform-tools/adb.exe'
PACKAGE = 'app.xfeedguard'
ENV = dict(os.environ, ADB_USB_LEGACY='1', ANDROID_USER_HOME=str(ROOT / '.tools/android-user'))


def adb(*args, binary=False):
    result = subprocess.run([str(ADB), *args], env=ENV, capture_output=True, check=True, timeout=30)
    return result.stdout if binary else result.stdout.decode('utf-8', errors='replace').strip()


def own_foreground():
    focus = next((line for line in adb('shell', 'dumpsys', 'window').splitlines() if 'mCurrentFocus=' in line), '')
    if f'{PACKAGE}/' not in focus:
        raise RuntimeError('X Feed Guard is not foreground; no tap or screenshot will be performed.')


def switch_node():
    own_foreground()
    raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
    own_foreground()
    start, end = raw.find('<?xml'), raw.rfind('</hierarchy>')
    if start < 0 or end < start:
        raise RuntimeError('Could not inspect the own-app control screen.')
    tree = ET.fromstring(raw[start:end + len('</hierarchy>')])
    switches = [node for node in tree.iter('node') if node.get('class') == 'android.widget.Switch']
    if len(switches) != 1 or (switches[0].get('text') != 'Protection' and switches[0].get('content-desc') != 'Protection'):
        raise RuntimeError('Expected one visible Protection power toggle on the home screen.')
    assert switches[0].get("clickable") == "true", "Power toggle must accept taps."
    return switches[0]


def preference():
    root = ET.fromstring(adb('exec-out', 'run-as', PACKAGE, 'cat', 'shared_prefs/guard.xml'))
    values = {node.get('name'): node.get('value') for node in root if node.tag == 'boolean'}
    return values.get('blocking') == 'true', values.get('observing') == 'true'


def save_screenshot(path):
    target = path.resolve()
    if not target.is_relative_to(ROOT):
        raise RuntimeError('QA screenshot must stay in the project directory.')
    own_foreground()
    png = adb('exec-out', 'screencap', '-p', binary=True)
    own_foreground()
    assert png.startswith(b'\x89PNG\r\n\x1a\n')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(png)
    print('Saved own-app QA screenshot: ' + str(target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--test-switch', action='store_true')
    parser.add_argument('--screenshot', type=Path)
    parser.add_argument('--test-navigation', action='store_true')
    args = parser.parse_args()
    if adb('get-state') != 'device':
        raise RuntimeError('An authorized USB phone is required.')
    print(adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c',
              'android.intent.category.LAUNCHER', '-f', '0x34000000', '-n', PACKAGE + '/.MainActivity'))
    node = switch_node()
    initial, _ = preference()
    assert (node.get('checked') == 'true') == initial
    print('PASS: launcher opens the home screen with one power toggle reflecting the saved choice.')
    if args.test_switch:
        for desired in (not initial, initial):
            node = switch_node()
            coords = [int(x) for x in re.findall(r'\d+', node.get('bounds', ''))]
            if len(coords) != 4:
                raise RuntimeError('Invalid observed switch bounds; no tap performed.')
            left, top, right, bottom = coords
            own_foreground()
            adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
            for attempt in range(5):
                if preference() == (desired, desired):
                    break
                time.sleep(0.2)
            assert preference() == (desired, desired), 'Switch failed to update protection and observation together.'
            assert (switch_node().get('checked') == 'true') == desired
            print('PASS: Protection ' + ('ON' if desired else 'OFF') + ' updated the interface and both saved preferences.')
            if args.screenshot and not desired:
                save_screenshot(args.screenshot.with_name(args.screenshot.stem + '-off.png'))
    if args.test_navigation:
        def wait_focus(package_or_activity):
            for _ in range(10):
                focus = next((line for line in adb('shell', 'dumpsys', 'window').splitlines() if 'mCurrentFocus=' in line), '')
                if package_or_activity in focus:
                    return
                time.sleep(0.2)
            raise RuntimeError('Expected foreground: ' + package_or_activity)
        def tap_label(label):
            own_foreground()
            raw = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
            own_foreground()
            tree = ET.fromstring(raw[raw.find('<?xml'):raw.rfind('</hierarchy>') + len('</hierarchy>')])
            nodes = [n for n in tree.iter('node') if n.get('text') == label or n.get('content-desc') == label]
            if len(nodes) != 1:
                raise RuntimeError('Expected a unique own-app control: ' + label)
            left, top, right, bottom = map(int, re.findall(r'\d+', nodes[0].get('bounds', '')))
            own_foreground()
            adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
        tap_label('Settings & diagnostics')
        if args.screenshot:
            save_screenshot(args.screenshot.with_name(args.screenshot.stem + '-settings.png'))
        tap_label('Notification settings')
        wait_focus('AppNotificationSettingsActivity')
        print('PASS: Settings & diagnostics opens and Notification settings opens Android notification controls.')
        adb('shell', 'input', 'keyevent', '4')
        wait_focus(PACKAGE + '/')
        tap_label('Back to protection')
        switch_node()
        print('PASS: returning from Android preserves settings; Back returns to the power screen.')
        tap_label('Open X')
        wait_focus('com.twitter.android/')
        print('PASS: X opens the official X app; no X hierarchy or screenshot collected.')
        adb('shell', 'am', 'start', '-W', '-f', '0x34000000', '-n', PACKAGE + '/.MainActivity')
        switch_node()
    if args.screenshot:
        save_screenshot(args.screenshot)


if __name__ == '__main__':
    main()
