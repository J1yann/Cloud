"""Capture the installed release through adb; no instrumentation or app-only hooks."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUTPUT = Path(os.environ['CLOUD_SCREENSHOT_DIR'])
OUTPUT.mkdir(parents=True, exist_ok=True)
REPORT = []


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], timeout=30, text=not binary, stderr=subprocess.DEVNULL)


def hierarchy():
    # A fixed path keeps UI XML (which contains user-visible metadata) out of job logs.
    adb('shell', 'uiautomator', 'dump', '/sdcard/cloud-screen.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/cloud-screen.xml'))


def click(match, timeout=15, last=False):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            nodes = [n for n in hierarchy().iter('node') if match(n.attrib)]
            if nodes:
                node = nodes[-1] if last else nodes[0]
                bounds = list(map(int, re.findall(r'\d+', node.attrib['bounds'])))
                x1, y1, x2, y2 = bounds
                if x2 > x1 and y2 > y1:
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    time.sleep(1)
                    return
        except (ET.ParseError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    raise RuntimeError('Requested screen control was not available')


def text(label, **kwargs):
    click(lambda a: a.get('text') == label, **kwargs)


def description(label):
    click(lambda a: a.get('content-desc') == label)


def capture(name, settle=2):
    time.sleep(settle)
    data = adb('exec-out', 'screencap', '-p', binary=True)
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError('Emulator did not return a PNG')
    (OUTPUT / f'{name}.png').write_bytes(data)
    # UI snapshots make missing network content diagnosable without dumping signed URLs/logcat.
    (OUTPUT / f'{name}.xml').write_bytes(ET.tostring(hierarchy(), encoding='utf-8'))
    REPORT.append({'screen': name, 'captured': True})
    print(f'Captured {name}', flush=True)


def back():
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)


def optional(name, action):
    try:
        action()
    except Exception as error:
        REPORT.append({'screen': name, 'captured': False, 'reason': type(error).__name__})
        print(f'Skipped {name}: {type(error).__name__}', flush=True)


def launch():
    result = adb('shell', 'am', 'start', '-W', '-n', 'cloud.music/.main.MainActivity')
    print(result.strip(), flush=True)
    time.sleep(3)


apk_dir = Path(os.environ['RUNNER_TEMP']) / 'cloud-apk'
apks = list(apk_dir.glob('*.apk'))
if len(apks) != 1:
    raise RuntimeError('Expected exactly one release APK')
adb('install', '-r', str(apks[0]))
adb('shell', 'wm', 'size', '1080x1920')
adb('shell', 'wm', 'density', '420')
adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
adb('shell', 'cmd', 'uimode', 'night', 'yes')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1')
for command in [('enter',), ('clock', '--es', 'hhmm', '0941'),
                ('battery', '--es', 'level', '100', '--es', 'plugged', 'false'),
                ('notifications', '--es', 'visible', 'false')]:
    adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '--es', 'command', *command)
launch()
# Real discovery requests can complete independently; allow artwork and shelves to arrive.
time.sleep(25)
try:
    if not any(n.attrib.get('package') == 'cloud.music' for n in hierarchy().iter('node')):
        capture('00-startup-diagnostic')
        nodes = hierarchy()
        print('Foreground UI: ' + json.dumps([
            {k: n.attrib.get(k, '') for k in ('package', 'text', 'content-desc')}
            for n in nodes.iter('node') if n.attrib.get('text') or n.attrib.get('content-desc')
        ])[:5000], flush=True)
        crash = adb('logcat', '-d', '-b', 'crash')
        print(re.sub(r'https?://\S+', '[URL]', crash)[-10000:], flush=True)
        raise RuntimeError('Cloud did not stay in the foreground')
    capture('01-home')

    def search_screen():
        text('Search', last=True)
        click(lambda a: a.get('class') == 'android.widget.EditText')
        adb('shell', 'input', 'text', 'Blinding%20Lights')
        adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
        time.sleep(18)
        capture('02-search')
    optional('02-search', search_screen)

    def player_screens():
        text('Songs')
        time.sleep(12)
        click(lambda a: a.get('class') == 'android.widget.TextView' and 'blinding lights' in a.get('text', '').lower())
        time.sleep(20)
        click(lambda a: a.get('resource-id') == 'cloud.music:id/tvTitle')
        capture('03-player', settle=8)
        text('Lyrics')
        capture('04-lyrics', settle=15)
        back()
    optional('03-player-and-04-lyrics', player_screens)

    # Reopen Main if a network-dependent navigation branch failed part-way through.
    adb('shell', 'am', 'force-stop', 'cloud.music')
    launch()
    time.sleep(8)
    text('Library', last=True)
    capture('05-library')
    description('Settings')
    capture('06-sidebar')
    text('Settings')
    capture('07-settings')
    back()
    description('Settings')
    text('About Cloud')
    capture('08-about')
finally:
    (OUTPUT / 'capture-report.json').write_text(json.dumps(REPORT, indent=2))
