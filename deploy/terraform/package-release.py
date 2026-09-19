#!/usr/bin/env python3
"""허용한 배포 코드와 관측 자료만 tar로 묶고 SHA256을 출력한다. 업로드는 하지 않는다."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import tarfile


spec = importlib.util.spec_from_file_location("runtime_files", Path(__file__).resolve().parents[1] / "linux/runtime-files.py")
runtime_files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime_files)


def release_files(root):
    files = runtime_files.release_files(root)
    required = {"deploy/terraform/bootstrap-node.sh", "deploy/terraform/trusted-proxies.py", "deploy/terraform/discovery-alerts.yml", "deploy/linux/bootstrap-host.sh", "deploy/linux/install-service.sh", "deploy/linux/runtime-files.py", "deploy/check-config.py", "deploy/compose.node-read.yml", "harness/prometheus/pg-queries.yaml", "harness/sql/summary.sql"}
    if not required.issubset({path.as_posix() for path in files}):
        raise ValueError("Release source is missing a required bootstrap or runtime file")
    return sorted(set(files))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    files = release_files(args.root.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, "w:gz") as archive:
        for path in files:
            archive.add(args.root / path, arcname=path.as_posix(), recursive=False)
    print(f"files={len(files)} sha256={hashlib.sha256(args.output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
