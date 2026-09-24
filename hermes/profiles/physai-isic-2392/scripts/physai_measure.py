#!/usr/bin/env python3
"""Measurement tick of a physical-AI bot (no model): run the repo's tests and
physics probe on its current main and append one ledger line.

Exit code is tick.cljk's own (0 OK / 1 findings / 2 could not measure), so a
red or unmeasured tick is recorded by the Hermes scheduler as a failed run
rather than delivered as a quiet success. The bot name is this profile's
directory name; nothing is passed on the command line.
"""
import os
import subprocess
import sys

PROFILE = os.path.basename(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROOT = os.environ.get("ITONAMI_ROOT", os.path.expanduser("~/github/com-junkawasaki"))
KBB = os.environ.get("KBB", "/opt/homebrew/bin/kbb")
TICK = os.path.join(ROOT, "scripts", "physical-ai-bots", "tick.cljk")

if __name__ == "__main__":
    raise SystemExit(subprocess.call([KBB, "--backend", "sci", TICK, "evidence", PROFILE], cwd=ROOT))
