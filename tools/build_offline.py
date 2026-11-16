#!/usr/bin/env python3
"""Build this checkout with cached Android tools, without a Gradle daemon."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
CACHE = ROOT / '.gradle-user-home/caches/modules-2/files-2.1'
SDK = Path(os.environ.get('ANDROID_HOME', '/home/cxz/Android/Sdk'))
BT = SDK / 'build-tools/34.0.0'
ANDROID = SDK / 'platforms/android-34/android.jar'
OUT = ROOT / 'app/build/offline'
OUT.mkdir(parents=True, exist_ok=True)


def run(args):
    subprocess.run([str(x) for x in args], check=True)


def artifact(group, name, version=None):
    files = list((CACHE / group / name).rglob('*.jar'))
    if version:
        files = [f for f in files if f.relative_to(CACHE).parts[2] == version]
    return sorted(files)[-1]


def version_key(text):
    return tuple(int(n) if n.isdigit() else n for n in re.split(r'[.-]', text))


# Select one cached version per library. Compose, navigation and test libraries
# are unused by this source tree.
selected = {}
for f in CACHE.rglob('*.aar'):
    group, name, version = f.relative_to(CACHE).parts[:3]
    if group.startswith(('androidx.compose', 'androidx.navigation', 'androidx.test', 'androidx.databinding')):
        continue
    key = (group, name)
    if key not in selected or version_key(version) > version_key(selected[key].relative_to(CACHE).parts[2]):
        selected[key] = f

deps, jars, packages, resources, manifests = [], [], [], [], []
for (group, name), aar in sorted(selected.items()):
    target = OUT / 'deps' / (group + '.' + name)
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(aar) as z:
        z.extractall(target)
    deps.append(target)
    if (target / 'classes.jar').exists():
        jars.append(target / 'classes.jar')
    jars.extend((target / 'libs').glob('*.jar'))
    manifest = target / 'AndroidManifest.xml'
    if manifest.exists():
        manifests.append(manifest)
        packages.append(ET.parse(manifest).getroot().get('package'))
    if (target / 'res').exists():
        compiled = target / 'resources.zip'
        run([BT / 'aapt2', 'compile', '--dir', target / 'res', '-o', compiled])
        resources.append(compiled)

# Runtime JAR dependencies, excluding build tools and duplicate collection APIs.
runtime = [
    ('androidx.concurrent', 'concurrent-futures', '1.1.0'),
    ('androidx.collection', 'collection-jvm', '1.4.0'),
    ('androidx.collection', 'collection-ktx', '1.4.0'),
    ('androidx.arch.core', 'core-common', '2.2.0'),
    ('androidx.constraintlayout', 'constraintlayout-solver', '2.0.1'),
    ('androidx.resourceinspection', 'resourceinspection-annotation', '1.0.1'),
    ('androidx.annotation', 'annotation-jvm', '1.7.0'),
    ('androidx.lifecycle', 'lifecycle-common', '2.7.0'),
    ('androidx.lifecycle', 'lifecycle-common-java8', '2.7.0'),
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
    ('org.jetbrains.kotlinx', 'kotlinx-coroutines-android', '1.8.0'),
    ('org.jetbrains', 'annotations', '23.0.0'),
    ('com.google.guava', 'listenablefuture', '1.0'),
]
jars.extend(artifact(*entry) for entry in runtime)
(OUT / 'dependencies.txt').write_text('\n'.join(str(x) for x in [*selected.values(), *jars]))

compiled_jar = OUT / 'current-code.jar'
unsigned = OUT / 'unsigned.apk'
if '--resume-dex' not in sys.argv:
    print('Merging manifests', flush=True)
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    ET.register_namespace('tools', 'http://schemas.android.com/tools')
    android_ns = '{http://schemas.android.com/apk/res/android}'
    tree = ET.parse(ROOT / 'main/AndroidManifest.xml')
    tree.getroot().set('package', 'com.cartographer.demo')
    tree.getroot().find('application').set(android_ns + 'debuggable', 'true')
    main_manifest = OUT / 'main-manifest.xml'
    tree.write(main_manifest, encoding='utf-8', xml_declaration=True)
    tool_cp = ':'.join(str(x) for x in CACHE.rglob('*.jar'))
    manifest = OUT / 'AndroidManifest.xml'
    run(['java', '-cp', tool_cp, 'com.android.manifmerger.Merger',
         '--main', main_manifest, '--libs', ':'.join(map(str, manifests)),
         '--property', 'MIN_SDK_VERSION=26', '--property', 'TARGET_SDK_VERSION=34',
         '--property', 'VERSION_CODE=1', '--property', 'VERSION_NAME=1.0',
         '--placeholder', 'applicationId=com.cartographer.demo',
         '--remove-tools-declarations', '--out', manifest])

    print('Compiling and linking current resources', flush=True)
    current = OUT / 'current-resources.zip'
    run([BT / 'aapt2', 'compile', '--dir', ROOT / 'main/res', '-o', current])
    generated = OUT / 'generated'
    if generated.exists():
        shutil.rmtree(generated)
    generated.mkdir(exist_ok=True)
    unsigned = OUT / 'unsigned.apk'
    args = [BT / 'aapt2', 'link', '-I', ANDROID, '--manifest', manifest,
            '--java', generated, '--extra-packages', ':'.join(sorted(set(filter(None, packages)))),
            '--auto-add-overlay', '-A', ROOT / 'main/assets', '-o', unsigned]
    for resource in [*resources, current]:
        args.extend(['-R', resource])
    run(args)

    print('Compiling generated resource classes', flush=True)
    classes = OUT / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(exist_ok=True)
    run(['javac', '-source', '17', '-target', '17', '-d', classes,
         *sorted(generated.rglob('*.java'))])
    print('Compiling current Kotlin sources', flush=True)
    compiler_cp = ':'.join(str(artifact(*entry)) for entry in [
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '1.9.22'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
        ('org.jetbrains', 'annotations', '23.0.0'),
    ])
    run(['java', '-Xmx4g', '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
         '-no-stdlib', '-no-reflect', '-jvm-target', '17',
         '-classpath', ':'.join(map(str, [ANDROID, classes, *jars])),
         '-d', classes, *sorted((ROOT / 'main/java').rglob('*.kt'))])

    compiled_jar = OUT / 'current-code.jar'
    with zipfile.ZipFile(compiled_jar, 'w') as z:
        for f in sorted(classes.rglob('*')):
            if f.is_file():
                z.write(f, f.relative_to(classes))
dex = OUT / 'dex'
if dex.exists():
    shutil.rmtree(dex)
dex.mkdir(exist_ok=True)
print('Converting current code and dependencies to DEX', flush=True)
run(['java', '-Xmx4g', '-cp', BT / 'lib/d8.jar', 'com.android.tools.r8.D8',
     '--debug', '--min-api', '26', '--lib', ANDROID, '--output', dex,
     compiled_jar, *jars])
with zipfile.ZipFile(unsigned, 'a') as z:
    for f in sorted(dex.glob('*.dex')):
        z.write(f, f.name, compress_type=zipfile.ZIP_DEFLATED)
    for f in sorted((ROOT / 'main/jniLibs/arm64-v8a').glob('*.so')):
        z.write(f, 'lib/arm64-v8a/' + f.name, compress_type=zipfile.ZIP_DEFLATED)
    # Preserve service loader metadata used by Kotlin coroutines and AndroidX.
    services = {}
    for jar in jars:
        with zipfile.ZipFile(jar) as library:
            for name in library.namelist():
                if name.startswith('META-INF/services/') and not name.endswith('/'):
                    services.setdefault(name, set()).update(library.read(name).decode().splitlines())
    for name, lines in services.items():
        z.writestr(name, '\n'.join(sorted(lines)) + '\n')

aligned = OUT / 'aligned.apk'
run([BT / 'zipalign', '-f', '-p', '4', unsigned, aligned])
destination = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
destination.parent.mkdir(parents=True, exist_ok=True)
keystore = ROOT / '.gradle-user-home/debug.keystore'
if not keystore.exists():
    shutil.copy2(Path('/home/cxz/.android/debug.keystore'), keystore)
run([BT / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'androiddebugkey',
     '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', destination, aligned])
run([BT / 'apksigner', 'verify', '--verbose', destination])
print(f'APK: {destination}', flush=True)
