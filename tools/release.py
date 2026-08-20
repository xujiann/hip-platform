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
TOOLS = ['init-hospital.py', 'import-patients.py', 'import-inpatients.py', 'import-documents.py',
         'bootstrap-demo.py', 'db-backup.ps1', 'db-restore.ps1', 'schedule-backup.ps1']
TOOL_DIRS = ['init-templates', 'migrate-templates']


def run(cmd, **kw):
    print('>', ' '.join(cmd))
    subprocess.run(cmd, check=True, cwd=ROOT, **kw)


def stamp_build_version(jar_path, version):
    """把发布版本写进 jar 内 build-info.properties（1.2.4 彩排）：
    pom 恒 0.1.0-SNAPSHOT，/actuator/info 曾自报 SNAPSHOT——发布包叫 1.2.3 而
    实例答不出自己是哪版，升级验收的"确认响应来自新产物"就没有可比对的值。"""
    import zipfile, re, shutil, tempfile
    tmp = tempfile.mktemp(suffix='.jar')
    with zipfile.ZipFile(jar_path) as zin, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == 'META-INF/build-info.properties':
                text = data.decode('utf-8')
                text = re.sub(r'build\.version=.*', 'build.version=' + version, text)
                data = text.encode('utf-8')
            zout.writestr(item, data)
    shutil.move(tmp, jar_path)
    print(f'> build-info 版本已改写为 {version}')


def check_manifest():
    """CHANGELOG 提到的 tools 脚本必须都在打包清单——1.1.5 的存量文书导入工具曾被漏掉，
    用发布包实施的医院第一天就拿不到它（1.1.9 B-14）。"""
    import re
    changelog = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
    mentioned = set(re.findall(r'(?:tools/)?([\w-]+\.py)', changelog))
    mentioned &= {p.name for p in (ROOT / 'tools').glob('*.py')}
    packaged = set(TOOLS)
    missing = {m for m in mentioned if m.endswith('.py') and not m.startswith('e2e')
               and m not in packaged and not m.startswith(('matrix', 'release', 'export'))}
    if missing:
        raise SystemExit(f'CHANGELOG 提到但未打包的工具：{sorted(missing)}——补入 TOOLS 或说明豁免')


def main():
    check_manifest()
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    version = sys.argv[1]
    skip_build = '--skip-build' in sys.argv

    if not skip_build:
        run(['mvn', '-q', 'clean', 'package', '-DskipTests'], shell=True)
        run(['npm', 'run', 'build', '--prefix', 'frontend/shell'], shell=True)

    # 多个 jar 时不能取 glob 第一个：会把旧版 jar 打包成新版本号发出去
    jars = sorted((ROOT / 'server' / 'target').glob('hip-server-*.jar'))
    if len(jars) != 1:
        raise SystemExit(f'server/target 下有 {len(jars)} 个 jar，请先 mvn clean：{[j.name for j in jars]}')
    jar = jars[0]
    dist = ROOT / 'frontend' / 'shell' / 'dist'
    if not dist.exists():
        raise SystemExit('前端 dist 不存在，先构建')

    out = ROOT / 'release' / f'hip-platform-{version}'
    if out.exists():
        shutil.rmtree(out)
    (out / 'docs').mkdir(parents=True)
    (out / 'tools').mkdir()

    shutil.copy2(jar, out / f'hip-server-{version}.jar')
    stamp_build_version(out / f'hip-server-{version}.jar', version)
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
    # E2E 全量入包（1.2.5 升级演练发现）：原先只拷 e2e-outpatient 一套，
    # 实施方拿包只能冒烟门诊，住院/医保/集成等 19 套验收做不了
    (out / 'tools' / 'e2elib.py').write_bytes((ROOT / 'tools' / 'e2elib.py').read_bytes())
    e2e_files = sorted((ROOT / 'tools').glob('e2e-*.py'))
    for f in e2e_files:
        shutil.copy2(f, out / 'tools' / f.name)
    print(f'> E2E 套件 {len(e2e_files)} 套入包')

    (out / '升级说明.md').write_text(
        f"""# 升级到 {version}

1. 停服（维护窗口约 15 分钟）
2. 备份数据库：tools/db-backup.ps1
3. 替换 hip-server jar 与前端 dist（整目录换）
4. 启动——Flyway 自动前滚新增迁移（配置/字典/规则库/实施段不受影响）
5. 回归抽查：HIP_E2E_BASE=http://<host>:8080/api python tools/e2e-outpatient.py（指向测试库；tools/ 下含全部 20 套）
6. 任一步失败：tools/db-restore.ps1 恢复备份，回退旧产物

有 impl 定制模块的医院：升级前先对新版重编译定制模块（ADR-0003）。
""", encoding='utf-8')

    zip_path = shutil.make_archive(str(out), 'zip', out.parent, out.name)
    size_mb = Path(zip_path).stat().st_size / 1024 / 1024
    print(f'\n发布物: {out}')
    print(f'压缩包: {zip_path}（{size_mb:.1f} MB）')


if __name__ == '__main__':
    main()
