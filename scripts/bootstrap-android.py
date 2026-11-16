#!/usr/bin/env python3
"""Fetch checksum-verified official Android SDK packages into a local directory."""
import argparse, hashlib, pathlib, urllib.request, zipfile, tempfile, shutil

PACKAGES = [
    ('https://dl.google.com/android/repository/platform-37.0_r02.zip', 'ed8ebf7f8822a4de5686d427f237d2fa30ff7410', 'platforms/android-37.0'),
    ('https://dl.google.com/android/repository/build-tools_r37_macosx.zip', 'eb080751b2b2028eb3604f571027d6f7b3c46321', 'build-tools/37.0.0'),
    ('https://dl.google.com/android/repository/build-tools_r36_macosx.zip', '199ae0047ee61e842f8ee0c6d3918e44fb9a1f83', 'build-tools/36.0.0'),
]

def main():
    parser=argparse.ArgumentParser(); parser.add_argument('--sdk', required=True); args=parser.parse_args()
    root=pathlib.Path(args.sdk).expanduser().resolve(); root.mkdir(parents=True,exist_ok=True)
    for url,expected,target in PACKAGES:
        dest=root / target
        if dest.exists():
            print('Present:',target,flush=True); continue
        print('Downloading:',url,flush=True)
        with tempfile.TemporaryDirectory(prefix='bridge-sdk-') as tmp:
            archive=pathlib.Path(tmp)/'package.zip'
            urllib.request.urlretrieve(url, archive)
            actual=hashlib.sha1(archive.read_bytes()).hexdigest()
            if actual != expected: raise SystemExit('Checksum mismatch: '+url)
            with zipfile.ZipFile(archive) as z:
                for info in z.infolist():
                    resolved=(pathlib.Path(tmp)/info.filename).resolve()
                    if pathlib.Path(tmp).resolve() not in resolved.parents: raise SystemExit('Unsafe archive path')
                z.extractall(tmp)
            dirs=[p for p in pathlib.Path(tmp).iterdir() if p.is_dir()]
            if len(dirs)!=1: raise SystemExit('Unexpected package layout: '+url)
            dest.parent.mkdir(parents=True,exist_ok=True); shutil.move(str(dirs[0]),str(dest))
            for name in ('aapt','aapt2','zipalign','apksigner','d8'):
                tool=dest/name
                if tool.exists(): tool.chmod(tool.stat().st_mode|0o111)
        print('Installed:',target,flush=True)
if __name__=='__main__': main()
