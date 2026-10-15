#!/usr/bin/env python3
"""Fail closed on network permission or unreviewed release APK attack surface.

Input must be text XML exported from the packaged APK with apkanalyzer manifest
print, not merely the source manifest. Policy changes require security review.
"""
import argparse
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
POLICY = Path(__file__).with_name("manifest-policy.json")


def qualified(name, package):
    if name.startswith("."):
        return package + name
    if "." not in name:
        return package + "." + name
    return name


def check_manifest(path, policy=None, require_sdk=True):
    policy = policy or json.loads(POLICY.read_text(encoding="utf-8"))
    root = ET.parse(path).getroot()
    errors = []
    package = root.get("package", "")
    if package != policy["package"]:
        errors.append(f"Unexpected release application ID: {package}")
    if root.tag != "manifest":
        errors.append("Not an Android manifest")
    sdk = root.find("uses-sdk")
    if sdk is None:
        if require_sdk:
            errors.append("Packaged manifest has no uses-sdk")
    else:
        for attribute, expected in [("minSdkVersion", policy["min_sdk"]), ("targetSdkVersion", policy["target_sdk"])]:
            if sdk.get(ANDROID + attribute) != str(expected):
                errors.append(f"Unexpected {attribute}: {sdk.get(ANDROID + attribute)}")
    for element in root:
        if element.tag.startswith("uses-permission"):
            name = element.get(ANDROID + "name", "")
            if name not in policy["allowed_permissions"]:
                errors.append(f"Unreviewed permission: {name}")
    # App-owned receiver-isolation permission must remain signature protected.
    dynamic_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    control_permission = package + ".permission.CONTROL_VAULT"
    declared = {}
    for permission in root.findall("permission"):
        name = permission.get(ANDROID + "name", "")
        declared[name] = permission.get(ANDROID + "protectionLevel")
        if name not in (dynamic_permission, control_permission) or permission.get(ANDROID + "protectionLevel") not in ("signature", "2", "0x2"):
            errors.append(f"Unreviewed or weakened custom permission: {name}")
    if declared.get(control_permission) not in ("signature", "2", "0x2"):
        errors.append("Missing signature-protected vault control permission")
    applications = root.findall("application")
    if len(applications) != 1:
        return errors + ["Expected exactly one application"]
    app = applications[0]
    if app.get(ANDROID + "allowBackup") != "false":
        errors.append("Platform backup/transfer is not disabled")
    if app.get(ANDROID + "backupAgent") is not None:
        errors.append("Unexpected platform backup agent")
    for attribute in ("fullBackupContent", "dataExtractionRules"):
        if not app.get(ANDROID + attribute): errors.append(f"Missing backup exclusion policy: {attribute}")
    for attribute in ("debuggable", "testOnly", "usesCleartextTraffic"):
        # Missing attributes are legal; offline permission gate is the primary defense.
        if app.get(ANDROID + attribute, "false") != "false":
            errors.append(f"Unsafe release flag {attribute}")
    allowed = policy["exported_components"]
    for component in app:
        if component.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        raw_name = component.get(ANDROID + "name", "")
        if not raw_name:
            errors.append("Unnamed component")
            continue
        name = qualified(raw_name, package)
        exported = component.get(ANDROID + "exported")
        if exported is None:
            # At our minimum SDK providers default private; other filtered components
            # historically default public. Explicit intents are not filter-restricted.
            exported = "true" if component.tag != "provider" and component.find("intent-filter") is not None else "false"
        if exported not in ("true", "false"):
            errors.append(f"Unresolved exported flag: {name} ({exported})")
            continue
        if exported == "false":
            continue
        key = component.tag + ":" + name
        baseline = allowed.get(key)
        if baseline is None:
            errors.append(f"Unreviewed exported component: {key}")
            continue
        permission = component.get(ANDROID + "permission", app.get(ANDROID + "permission", ""))
        if permission != baseline["permission"]:
            errors.append(f"Changed caller permission: {key} ({permission})")
        actions = {a.get(ANDROID + "name", "") for a in component.findall("intent-filter/action")}
        if actions != set(baseline["actions"]):
            errors.append(f"Changed exported actions: {key} ({sorted(actions)})")
        data = [{attribute.removeprefix(ANDROID): value for attribute, value in item.attrib.items()}
                for item in component.findall("intent-filter/data")]
        canonical = lambda values: sorted(json.dumps(value, sort_keys=True) for value in values)
        if canonical(data) != canonical(baseline.get("data", [])):
            errors.append(f"Changed exported URI/data filters: {key}")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifests", nargs="+", type=Path)
    args = parser.parse_args()
    failed = False
    for path in args.manifests:
        try:
            errors = check_manifest(path)
        except (OSError, ValueError, ET.ParseError) as error:
            errors = [f"Cannot inspect manifest: {error}"]
        if errors:
            failed = True
            for error in errors:
                print(f"{path}: {error}", file=sys.stderr)
        else:
            print(f"{path}: release manifest security policy passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
