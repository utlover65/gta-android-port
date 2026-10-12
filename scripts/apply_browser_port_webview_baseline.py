#!/usr/bin/env python3
"""Apply the small WebView compatibility-gate patch to an existing GTA Android repo.

Usage: python scripts/apply_browser_port_webview_baseline.py /path/to/gta-android-port
The script only edits the AndroidManifest, MainActivity, and README. It never copies or
modifies game assets or the existing native Dawn/Vulkan implementation.
"""
from __future__ import annotations

import argparse
from pathlib import Path
import sys

PERMISSION = '    <uses-permission android:name="android.permission.INTERNET" />'
ACTIVITY = '''        <activity
            android:name=".BrowserPortActivity"
            android:exported="false"
            android:screenOrientation="sensorLandscape" />
'''
BUTTON = '''        Button browserPortButton = new Button(this);
        browserPortButton.setText("Browser port");
        browserPortButton.setAllCaps(false);
        browserPortButton.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, BrowserPortActivity.class)));
        controls.addView(browserPortButton,
                new LinearLayout.LayoutParams(0, -2, 1));

'''
README_SECTION = '''

## Browser port compatibility gate

The **Browser port** button opens the live browser build in an Android WebView using a low-memory profile. The overlay reports whether that exact WebView exposes WebGPU, OffscreenCanvas, SharedArrayBuffer, and cross-origin isolation. This is an online compatibility test, not yet an offline/native integration; the original Dawn and Vulkan probes remain available.

The integration notes in `docs/browser-runtime-integration.md` explain the runtime contracts found in the provided bundle and why its ZIP is not yet a complete offline deployment.
'''


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise ValueError(f"Expected exactly one {label} anchor, found {count}; refusing to guess.")
    return text.replace(old, new, 1)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", type=Path, help="Root of utlover65/gta-android-port")
    args = parser.parse_args()
    root = args.repo.resolve()
    manifest = root / "app/src/main/AndroidManifest.xml"
    activity = root / "app/src/main/java/org/utlover65/astravulkanprobe/MainActivity.java"
    browser_activity = root / "app/src/main/java/org/utlover65/astravulkanprobe/BrowserPortActivity.java"
    readme = root / "README.md"

    for p in (manifest, activity, browser_activity, readme):
        if not p.is_file():
            print(f"Missing expected project file: {p}", file=sys.stderr)
            return 2

    m = manifest.read_text(encoding="utf-8")
    a = activity.read_text(encoding="utf-8")
    r = readme.read_text(encoding="utf-8")

    changed = []
    if 'android.permission.INTERNET' not in m:
        m = replace_once(m, '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n',
                         '<manifest xmlns:android="http://schemas.android.com/apk/res/android">\n' + PERMISSION + '\n',
                         'manifest root')
        changed.append(manifest)
    if '.BrowserPortActivity' not in m:
        m = replace_once(m, '    </application>', ACTIVITY + '    </application>', 'application close')
        changed.append(manifest)
    if 'import android.content.Intent;' not in a:
        a = replace_once(a, 'import android.app.Activity;\n',
                         'import android.app.Activity;\nimport android.content.Intent;\n', 'Activity import')
        changed.append(activity)
    if 'BrowserPortActivity.class' not in a:
        a = replace_once(a, '        FrameLayout.LayoutParams controlsParams =\n', BUTTON + '        FrameLayout.LayoutParams controlsParams =\n', 'bottom control row')
        changed.append(activity)
    if '## Browser port compatibility gate' not in r:
        r += README_SECTION
        changed.append(readme)

    # Do all anchor validation above before writing any file.
    if manifest in changed:
        manifest.write_text(m, encoding="utf-8")
    if activity in changed:
        activity.write_text(a, encoding="utf-8")
    if readme in changed:
        readme.write_text(r, encoding="utf-8")

    print("Browser Port compatibility activity patch applied." if changed else "Patch already applied; no files changed.")
    for p in dict.fromkeys(changed):
        print(f"updated {p.relative_to(root)}")
    print("No Dawn/Vulkan native sources or game assets were modified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
