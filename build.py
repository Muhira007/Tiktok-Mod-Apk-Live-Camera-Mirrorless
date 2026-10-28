"""Monorepo build entry point. Does not install to a phone."""
import runpy
from pathlib import Path

if __name__ == '__main__':
    runpy.run_path(str(Path(__file__).resolve().parent / 'apps/sony-live/build.py'), run_name='__main__')
