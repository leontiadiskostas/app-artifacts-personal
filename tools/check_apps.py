"""Checks apps.json before anything is built, so a typo fails early with a clear message."""
import json
import re
import sys

apps = json.load(open(sys.argv[1]))
errors = []
seen = set()
if not isinstance(apps, list) or not apps:
    errors.append("apps.json must be a list with at least one app")
for i, a in enumerate(apps if isinstance(apps, list) else []):
    where = f"app #{i + 1} ({a.get('name', '?')})"
    if not re.fullmatch(r"[a-z][a-z0-9]{0,30}", str(a.get("id", ""))):
        errors.append(f"{where}: id must be lowercase letters/digits, starting with a letter")
    if a.get("id") in seen:
        errors.append(f"{where}: id '{a.get('id')}' is used twice")
    seen.add(a.get("id"))
    if not str(a.get("name", "")).strip():
        errors.append(f"{where}: name is empty")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", str(a.get("color", ""))):
        errors.append(f"{where}: color must look like #1F3A5F")
    if not str(a.get("label", "")).strip():
        errors.append(f"{where}: label (1-3 letters on the icon) is empty")
    if not re.fullmatch(r"https://claude\.ai/[A-Za-z0-9/_\-]+", str(a.get("url", ""))):
        errors.append(f"{where}: url must be a https://claude.ai/... artifact link")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print(f"{len(apps)} app(s) OK")
