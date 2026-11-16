#!/usr/bin/env python3
"""Run the checkout's JVM tests against the completed offline application build."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
CACHE = ROOT / '.gradle-user-home/caches/modules-2/files-2.1'
BUILD = ROOT / 'app/build/offline'


def jar(group, name, version=None):
    return next(f for f in (CACHE / group / name).rglob('*.jar')
                if version is None or f.relative_to(CACHE).parts[2] == version)


def main():
    compiler = [jar(*entry) for entry in [
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.intellij.deps', 'trove4j', None),
        ('org.jetbrains', 'annotations', '23.0.0'),
    ]]
    sdk = Path(os.environ.get('ANDROID_HOME', '/home/cxz/Android/Sdk'))
    runtime = [BUILD / 'current-code.jar', sdk / 'platforms/android-34/android.jar',
               jar('junit', 'junit', '4.13.2'), jar('org.hamcrest', 'hamcrest-core')]
    runtime += [Path(line) for line in (BUILD / 'dependencies.txt').read_text().splitlines()
                if line.endswith('.jar')]
    classpath = ':'.join(map(str, runtime))
    destination = BUILD / 'test-classes'
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir()
    tests = sorted((ROOT / 'test/java').rglob('*Test.kt'))
    subprocess.run(['java', '-Xmx1g', '-cp', ':'.join(map(str, compiler)),
                    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                    '-jvm-target', '17', '-classpath', classpath, '-d', str(destination),
                    *map(str, tests)], check=True)
    subprocess.run(['java', '-cp', str(destination) + ':' + classpath,
                    'org.junit.runner.JUnitCore',
                    *('com.cartographer.demo.' + f.stem for f in tests)], check=True)


if __name__ == "__main__":
    main()
