#!/usr/bin/env python3
"""Evidence for a physical-AI bot's growth tick: the same measurement as
physai_measure.py, printed for the agent and always exit 0.

A Hermes cron script that exits non-zero reaches the agent as a failure
message instead of as evidence; a red tick is exactly when the agent must read
it, so the status travels in the text (STATUS line), not in the exit code.
"""
import os
import subprocess
import sys

PROFILE = os.path.basename(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ROOT = os.environ.get("ITONAMI_ROOT", os.path.expanduser("~/github/com-junkawasaki"))
KBB = os.environ.get("KBB", "/opt/homebrew/bin/kbb")
TICK = os.path.join(ROOT, "scripts", "physical-ai-bots", "tick.cljk")

if __name__ == "__main__":
    r = subprocess.run([KBB, "--backend", "sci", TICK, "evidence", PROFILE, "--hermes"],
                       cwd=ROOT, capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    if r.returncode != 0:
        print(f"STATUS\tUNMEASURED\nERROR\ttick.cljk exited {r.returncode}: {r.stderr.strip()[-600:]}")
    raise SystemExit(0)
