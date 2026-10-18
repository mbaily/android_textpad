#!/usr/bin/env python3
"""Build a deterministic Vim package ZIP, including generated help tags."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def package(target):
    root = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix='textpad-vim-package-') as folder:
        stage = Path(folder) / 'textpad-tasks'
        for name in ('plugin', 'autoload', 'doc'):
            shutil.copytree(root / 'vim' / name, stage / name)
        shutil.copyfile(root / 'vim/README.md', stage / 'README.md')
        doc = str(stage / 'doc').replace("'", "''")
        subprocess.run([os.environ.get('VIM_BINARY', 'vim'), '-Nu', 'NONE', '-n', '-es', '-i', 'NONE',
                        '-c', "execute 'helptags ' . fnameescape('" + doc + "')", '-c', 'qa!'], check=True)
        target.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(target, 'w') as archive:
            for source in sorted(stage.rglob('*')):
                if source.is_file():
                    entry = zipfile.ZipInfo(source.relative_to(stage.parent).as_posix(), date_time=(2020, 1, 1, 0, 0, 0))
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    entry.external_attr = 0o100644 << 16
                    archive.writestr(entry, source.read_bytes())
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_name(target.name + '.sha256').write_text(digest + '  ' + target.name + '\n', encoding='ascii')
    target.with_name('textpad-tasks-vim-README.txt').write_bytes((root / 'vim/README.md').read_bytes())
    print(f'{target}: {target.stat().st_size} bytes; SHA-256 {digest}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent.parent /
                        'server/textpad_server/static/textpad-tasks-vim.zip')
    package(parser.parse_args().output)
