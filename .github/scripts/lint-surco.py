#!/usr/bin/env python3
"""Resumen del informe de Android Lint de Surco. Falla si hay algún error (Error o Fatal)."""
import sys
import xml.etree.ElementTree as ET
from collections import Counter

root = ET.parse(sys.argv[1]).getroot()
issues = root.findall("issue")
by_sev = Counter(i.get("severity") for i in issues)
print("lint:", dict(by_sev))
errors = [i for i in issues if i.get("severity") in ("Error", "Fatal")]
warnings = [i for i in issues if i.get("severity") == "Warning"]
def where(i):
    loc = i.find("location")
    if loc is None: return "?"
    f = loc.get("file", "?").split("/surco/")[-1]
    return f"{f}:{loc.get('line', '?')}"
for i in errors:
    # La línea ::error:: se vuelve anotación y desaparece del log: va también en claro.
    print(f"  ERROR {i.get('id')} {where(i)} {i.get('message')}")
    print(f"::error::lint {i.get('id')} {where(i)} {i.get('message')}")
infos = [i for i in issues if i.get("severity") == "Information"]
print("informativos:", dict(Counter(i.get("id") for i in infos)))
print("avisos por tipo:", dict(Counter(i.get("id") for i in warnings)))
for i in warnings:
    print(f"  aviso {i.get('id')} {where(i)} {i.get('message')}")
sys.exit(1 if errors else 0)
