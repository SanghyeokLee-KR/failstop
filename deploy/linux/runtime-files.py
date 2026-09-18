#!/usr/bin/env python3
"""운영 설치와 release에 넣을 소스만 선택한다. 런타임 비밀과 덤프는 복사하지 않는다."""
import argparse
import os
from pathlib import Path
import shutil

ROOT_FILES = {"check-config.py", "provision-runtime-roles.sh", "provision-runtime-roles.sql",
              "pg-init-replication.sh", "pg-standby-entrypoint.sh"}
CODE_SUFFIXES = {".sh", ".py", ".service", ".timer", ".conf", ".sql"}


def release_files(root):
    files = []
    for path in (root / "deploy").rglob("*"):
        relative = path.relative_to(root)
        parts = relative.parts
        if any(part.startswith(".") or part in {"tests", "lab", "__pycache__", "state", "reports", "backups", "targets"} for part in parts):
            continue
        include = len(parts) == 2 and (path.name in ROOT_FILES or (path.name.startswith("compose.") and path.suffix == ".yml"))
        if len(parts) > 2 and parts[1] == "console":
            include = ((len(parts) == 3 and (path.suffix == ".py" or path.name == "Dockerfile") and not path.name.startswith("test_"))
                       or (len(parts) > 3 and parts[2] == "static" and path.suffix in {".html", ".css", ".js", ".svg", ".woff2", ".txt"}))
        if len(parts) > 2 and parts[1] in {"linux", "observability", "reporting", "backup"}:
            include = path.suffix in CODE_SUFFIXES or path.name.endswith(".example.json")
            include = include or parts[1] == "observability" and path.suffix in {".yml", ".yaml"}
        include = include or relative.as_posix() == "deploy/backup/backup.env.example"
        include = include or relative.as_posix() in {"deploy/terraform/bootstrap-node.sh", "deploy/terraform/discover-targets.py", "deploy/terraform/trusted-proxies.py", "deploy/terraform/discovery-alerts.yml"}
        include = include or (relative.as_posix().startswith("deploy/terraform/systemd/") and path.suffix in {".service", ".timer"})
        if include and path.is_symlink():
            raise ValueError(f"Release source cannot be a symlink: {relative}")
        if include and path.is_file():
            files.append(relative)
    for subtree in ["harness/prometheus/rules", "harness/grafana/dashboards", "harness/blackbox"]:
        for path in (root / subtree).rglob("*"):
            if path.is_symlink():
                raise ValueError("Observability source cannot be a symlink")
            if path.is_file() and path.suffix in {".yml", ".yaml", ".json"}:
                files.append(path.relative_to(root))
    for name in ["harness/sql/summary.sql", "harness/prometheus/pg-queries.yaml"]:
        asset = root / name
        if asset.is_symlink():
            raise ValueError("Operational asset cannot be a symlink")
        if asset.is_file():
            files.append(asset.relative_to(root))
    return sorted(set(files))


def copy_runtime(root, target):
    for relative in release_files(root):
        destination = target / relative
        for part in [destination, *destination.parents]:
            if part.is_symlink():
                raise ValueError("Runtime destination cannot contain symlinks")
            if part == target:
                break
        destination.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        if (root / relative).resolve() != destination.resolve():
            shutil.copyfile(root / relative, destination)
        destination.chmod(0o755 if destination.suffix == ".sh" else 0o644)
        if os.geteuid() == 0:
            os.chown(destination, 0, 0)
            for directory in destination.parents:
                if directory == target.parent:
                    break
                os.chown(directory, 0, 0)
                directory.chmod(0o755)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--target", required=True, type=Path)
    options = parser.parse_args()
    copy_runtime(options.root.resolve(), options.target.absolute())
