#!/usr/bin/env python3
"""Build the dependency-free, cross-platform CLI zip application reproducibly."""
import argparse
import hashlib
from pathlib import Path
import zipfile


def package(target):
    root = Path(__file__).resolve().parent.parent
    files = {'__main__.py': b'from textpad_sync.cli import main\nraise SystemExit(main())\n'}
    for source in sorted((root / 'cli/textpad_sync').glob('*.py')):
        files['textpad_sync/' + source.name] = source.read_bytes()
    files['README.md'] = (root / 'cli/README.md').read_bytes()
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('wb') as stream:
        stream.write(b'#!/usr/bin/env python3\n')
        with zipfile.ZipFile(stream, 'w') as archive:
            for name, content in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                entry.external_attr = 0o100644 << 16
                archive.writestr(entry, content)
    target.chmod(0o755)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_name(target.name + '.sha256').write_text(digest + '  ' + target.name + '\n', encoding='ascii')
    target.with_name('textpad-sync-README.txt').write_bytes(files['README.md'])
    print(f'{target}: {target.stat().st_size} bytes; SHA-256 {digest}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent.parent /
                        'server/textpad_server/static/textpad-sync.pyz')
    package(parser.parse_args().output)
