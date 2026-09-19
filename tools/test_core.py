#!/usr/bin/env python3
from pathlib import Path
import subprocess
ROOT=Path(__file__).resolve().parents[1]
classes=ROOT/'build'/'tests';classes.mkdir(parents=True,exist_ok=True)
sources=list((ROOT/'core'/'src'/'main'/'java').rglob('*.java'))+[ROOT/'tests'/'CoreTests.java',ROOT/'tests'/'RenderPreview.java']
subprocess.run(['java','com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(classes),*[str(p)for p in sources]],check=True)
subprocess.run(['java','-cp',str(classes),'CoreTests'],check=True)
subprocess.run(['java','-Djava.awt.headless=true','-cp',str(classes),'RenderPreview',str(ROOT/'verification'/'renders')],check=True)
