#!/usr/bin/env python3
"""Reproducible offline ZIP; never reads configuration, credentials or state."""
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parents[2]
source = root / 'scripts/runtime/deadline_monitor.py'
output = root / 'infra/terraform/environments/dev/runtime/deadline-monitor.zip'
info = zipfile.ZipInfo('deadline_monitor.py', (2026, 11, 23, 0, 0, 0))
info.external_attr = 0o644 << 16
with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    archive.writestr(info, source.read_bytes())
print('OFFLINE monitor ZIP packaged')
