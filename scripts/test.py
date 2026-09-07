#!/usr/bin/env python3
"""Portable counterpart of test.sh for Windows, macOS and Linux."""
import argparse
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ci", action="store_true", help="Keep functional assertions; collect timing without workstation budgets or native DeepSlice sandbox certification")
options = parser.parse_args()

root = Path(__file__).resolve().parents[1]
env = os.environ.copy()
java_home = env.get("FIJI_JAVA_HOME") or env.get("JAVA_HOME")
if java_home:
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")
subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_*.py"], cwd=root, env=env, check=True)
args = [env.get("MAVEN_BIN", "mvn.cmd" if os.name == "nt" else "mvn"), "--batch-mode", "--no-transfer-progress"]
if env.get("MAVEN_REPOSITORY"):
    args.append("-Dmaven.repo.local=" + env["MAVEN_REPOSITORY"])
if options.ci:
    args.extend(["-Datlasalign.performanceChecks=false", "-Datlasalign.nativeSandboxChecks=false"])
subprocess.run(args + ["verify"], cwd=root, env=env, check=True)
