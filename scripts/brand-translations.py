#!/usr/bin/env python3
# Writes app/src/brand/res/values-*/strings_brand.xml: upstream's translations of the strings Inilauncher shows,
# with "Olauncher" changed to "Inilauncher". Run from the repo root after an upstream merge: python3 scripts/brand-translations.py .

import re, sys, pathlib
root = pathlib.Path(sys.argv[1])
SHOWN = {"set_as_default_launcher_first","admin_permission_message","keyboard_message","wallpaper_message",
 "accessibility_service_description","please_set_olauncher_as_default_first","hidden_apps_message",
 "accessibility_disclosure","launcher_failed_to_lock_device","olauncher_is_not_default_launcher","app_usage_message"}
pat = re.compile(r'(<string name="([a-z_0-9]+)"[^>]*>)(.*?)(</string>)', re.S)
for f in sorted((root/"app/src/main/res").glob("values-*/strings.xml")):
    out = []
    for m in pat.finditer(f.read_text(encoding="utf-8")):
        if m.group(2) in SHOWN and "Olauncher" in m.group(3):
            out.append("    " + m.group(1) + m.group(3).replace("Olauncher", "Inilauncher") + m.group(4))
    if not out: continue
    d = root/"app/src/brand/res"/f.parent.name; d.mkdir(parents=True, exist_ok=True)
    (d/"strings_brand.xml").write_text('<?xml version="1.0" encoding="utf-8"?>\n<!-- Inilauncher branding: upstream\'s translations with the app name changed (generated from\n     app/src/main/res/' + f.parent.name + '/strings.xml). See values/strings_brand.xml. -->\n<resources>\n' + "\n".join(out) + "\n</resources>\n", encoding="utf-8")
    print(f.parent.name, len(out))
