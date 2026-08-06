# -*- coding: utf-8 -*-
"""发布打包：python tools/release.py <版本号> [--skip-build]
产出 release/hip-platform-<版本>/（jar + 前端 dist + 部署编排 + 实施文档 + CHANGELOG）并压缩 zip。
前提：仓库根目录执行；--skip-build 复用已有构建产物。
"""
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.stdout.reconfigure(encoding='utf-8')

DOCS = ['功能清单.md', '多医院部署操作指南.md', '部署手册.md', '配置手册.md', '操作手册.md', '培训脚本.md',
        'adr/ADR-0002-医保SDK实现约定.md', 'adr/ADR-0003-实施定制层规约.md']
TOOLS = ['init-hospital.py', 'import-patients.py', 'bootstrap-demo.py', 'db-backup.ps1', 'db-restore.ps1',
         'schedule-backup.ps1']
TOOL_DIRS = ['init-templates', 'migrate-templates']


def run(cmd, **kw):
    print('>', ' '.join(cmd))
    subprocess.run(cmd, check=True, cwd=ROOT, **kw)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    version = sys.argv[1]
    skip_build = '--skip-build' in sys.argv

    if not skip_build:
        run(['mvn', '-q', 'clean', 'package', '-DskipTests'], shell=True)
        run(['npm', 'run', 'build', '--prefix', 'frontend/shell'], shell=True)

    jar = next((ROOT / 'server' / 'target').glob('hip-server-*.jar'))
    dist = ROOT / 'frontend' / 'shell' / 'dist'
    assert dist.exists(), '前端 dist 不存在，先构建'

    out = ROOT / 'release' / f'hip-platform-{version}'
    if out.exists():
        shutil.rmtree(out)
    (out / 'docs').mkdir(parents=True)
    (out / 'tools').mkdir()

    shutil.copy2(jar, out / f'hip-server-{version}.jar')
    shutil.copytree(dist, out / 'dist')
    shutil.copytree(ROOT / 'deploy', out / 'deploy')
    shutil.copy2(ROOT / 'CHANGELOG.md', out / 'CHANGELOG.md')
    for d in DOCS:
        dst = out / 'docs' / Path(d).name
        shutil.copy2(ROOT / 'docs' / d, dst)
    for t in TOOLS:
        shutil.copy2(ROOT / 'tools' / t, out / 'tools' / t)
    for d in TOOL_DIRS:
        shutil.copytree(ROOT / 'tools' / d, out / 'tools' / d)
    (out / 'tools' / 'e2elib.py').write_bytes((ROOT / 'tools' / 'e2elib.py').read_bytes())
    shutil.copy2(ROOT / 'tools' / 'e2e-outpatient.py', out / 'tools' / 'e2e-outpatient.py')

    (out / '升级说明.md').write_text(
        f"""# 升级到 {version}

1. 停服（维护窗口约 15 分钟）
2. 备份数据库：tools/db-backup.ps1
3. 替换 hip-server jar 与前端 dist（整目录换）
4. 启动——Flyway 自动前滚新增迁移（配置/字典/规则库/实施段不受影响）
5. 回归抽查：python tools/e2e-outpatient.py（指向测试库）
6. 任一步失败：tools/db-restore.ps1 恢复备份，回退旧产物

有 impl 定制模块的医院：升级前先对新版重编译定制模块（ADR-0003）。
""", encoding='utf-8')

    zip_path = shutil.make_archive(str(out), 'zip', out.parent, out.name)
    size_mb = Path(zip_path).stat().st_size / 1024 / 1024
    print(f'\n发布物: {out}')
    print(f'压缩包: {zip_path}（{size_mb:.1f} MB）')


if __name__ == '__main__':
    main()
