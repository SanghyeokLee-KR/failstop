#!/usr/bin/env python3
"""demo-reporting-local.py. 로컬 시연용 보고 서비스(deploy/reporting)를 이 PC에서 띄운다.

학교 AWS의 보고 서비스와 같은 코드·같은 포트(9088)다. 다른 점은 넷이다.
- AI 참고 분석은 이 PC의 온프레미스 모델(Ollama, 기본 gpt-oss:20b)이 한다(analyzer=ollama, 루프백).
- 수집 범위는 customer=exchange, environment=local-lab이다(scope.py). 카드 머리에는 '로컬 실습'으로 나온다.
- 질의의 역할 정규식에 host 를 더한다. 디스크·백업 지표가 Docker 호스트(role=host)에서 온다(build_config 주석).
- PDF·HTML 링크는 http://127.0.0.1:9088 이라 이 PC에서만 열린다(로컬 시험용 루프백 주소).

비밀은 파일로만 다룬다. Teams 웹훅은 harness/demo/reporting.local/teams.env 한 줄(URL 또는 TEAMS_WEBHOOK_URL=URL)에
사용자가 직접 넣는다. 이 스크립트는 그 값을 출력하지 않고 호스트 이름만 꺼내 허용 목록(REPORT_TEAMS_ALLOWED_HOSTS)에
넣는다. 수신 토큰과 보고서 서명 키는 없으면 무작위로 만든다. 폴더 이름이 .local로 끝나 커밋되지 않는다(.gitignore *.local).

사용(저장소 루트):
  python harness/scripts/demo-reporting-local.py init        토큰·서명 키·설정만 만든다(관리 평면·Alertmanager가 토큰 파일을 먼저 마운트한다)
  python harness/scripts/demo-reporting-local.py preview     지금 지표로 보고를 만들어 보기만 한다(Teams로 보내지 않음)
  python harness/scripts/demo-reporting-local.py serve       /alerts·/msp/events를 받는다. teams.env가 있으면 Teams로 보낸다
  python harness/scripts/demo-reporting-local.py serve --no-delivery   받기만 하고 보내지 않는다
  python harness/scripts/demo-reporting-local.py test-send   운영 자료 없는 연결 시험 카드 1건을 Teams로 보낸다
"""
import argparse
import json
import os
import secrets
import subprocess
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORTING = ROOT / "deploy" / "reporting"
HOME = ROOT / "harness" / "demo" / "reporting.local"
FONT_DIR = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "Windows" / "Fonts"
PORT = 9088
ROLE_FROM = 'role=~"app|reader|data|standby|probe"'
ROLE_TO = 'role=~"app|reader|data|standby|probe|host"'

sys.path.insert(0, str(REPORTING))
from scope import scoped_config  # noqa: E402
from secret_inputs import read_secret  # noqa: E402
from transport import restricted_https  # noqa: E402


def secret_file(name, size=32):
    path = HOME / name
    if not path.is_file():
        path.write_text(secrets.token_hex(size) + "\n", encoding="ascii")
    return path


def font(names):
    for name in names:
        for folder in (FONT_DIR, Path("C:/Windows/Fonts")):
            if (folder / name).is_file():
                return str(folder / name)
    return None


def build_config(model, base_url, think):
    config = json.loads((REPORTING / "config.example.json").read_text(encoding="utf-8"))
    config = scoped_config(config, "exchange", "local-lab")
    # 로컬 판 변환. Grafana 로컬 판(harness/grafana/build-local-msp.py 규칙 2)과 같다. 로컬 노드는 컨테이너라 노드마다
    # node_exporter 가 없고 Docker 호스트 한 대의 node_exporter 가 role=host 로 들어온다. host 를 빼면 보고서의
    # 디스크·inode 여유와 최근 백업 경과가 '자료 없음'이 된다(2026-09-27 녹화 3회차 보고서)
    changed = 0
    for query in config["queries"]:
        if ROLE_FROM in query["expr"]:
            query["expr"] = query["expr"].replace(ROLE_FROM, ROLE_TO)
            changed += 1
    if not changed:
        raise SystemExit("보고 질의에서 역할 정규식을 찾지 못했다. config.example.json 구조가 바뀌었다")
    config.update(prometheus_url="http://127.0.0.1:59090", alertmanager_url="http://127.0.0.1:59093",
                  analyzer="ollama", ai_model=model, ai_base_url=base_url, ai_think=think, ai_timeout=30)
    regular = font(["Pretendard-Regular.ttf", "NotoSansKR-Regular.ttf", "malgun.ttf"])
    bold = font(["Pretendard-Bold.ttf", "NotoSansKR-Bold.ttf", "malgunbd.ttf"])
    if regular and bold:
        (HOME / "artifacts").mkdir(exist_ok=True)
        config["artifacts"] = {"enabled": True, "public_base_url": f"http://127.0.0.1:{PORT}",
                               "directory": str(HOME / "artifacts"), "font": regular, "font_bold": bold,
                               "ttl_seconds": 604800}
    path = HOME / "reporting.json"
    path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("init", "preview", "serve", "test-send"))
    # 관리 평면(harness/docker-compose.demo-admin.yml)과 같은 모델을 쓴다. 이 PC에서는 gpt-oss:20b가 GPU에 다 올라간다.
    # gpt-oss는 think를 low·medium·high로 받고, qwen3.6은 false로 끈다
    parser.add_argument("--model", default="gpt-oss:20b")
    parser.add_argument("--think", default=None, help="low|medium|high|false. 생략하면 gpt-oss는 low, 그 밖은 false")
    parser.add_argument("--ollama", default="http://127.0.0.1:11434")
    parser.add_argument("--no-delivery", action="store_true")
    args = parser.parse_args()
    HOME.mkdir(parents=True, exist_ok=True)
    token = secret_file("reporter-token")
    artifact_key = secret_file("artifact-key")
    think = args.think if args.think is not None else ("low" if args.model.startswith("gpt-oss") else "false")
    think = {"false": False, "true": True}.get(think, think)
    config = build_config(args.model, args.ollama, think)
    state = HOME / "outbox.sqlite3"
    if args.command == "init":
        print(f"준비 완료: {HOME} (토큰·서명 키·설정)")
        return
    env = dict(os.environ, PYTHONIOENCODING="utf-8",
               REPORT_INGEST_TOKEN=token.read_text(encoding="ascii").strip(),
               REPORT_ARTIFACT_KEY_FILE=str(artifact_key), REPORT_DELIVERY_ENABLED="false")
    teams = HOME / "teams.env"
    command = [sys.executable, str(REPORTING / "reporter.py"), args.command, "--config", str(config), "--state", str(state)]
    if args.command == "preview":
        out = HOME / "preview.html"
        subprocess.run(command + ["--output", str(out), "--format", "html"], env=env, check=True)
        print(f"미리보기: {out}")
        return
    if teams.is_file() and not args.no_delivery:
        url = read_secret(str(teams), "teams")  # install.py와 같은 읽기. 셸로 source하지 않는다
        host = urllib.parse.urlsplit(url).hostname or ""
        restricted_https(url, host, teams=True)  # Teams Workflows 주소 모양이 아니면 여기서 멈춘다
        env.update(REPORT_TEAMS_SECRET_FILE=str(teams), REPORT_TEAMS_ALLOWED_HOSTS=host, REPORT_DELIVERY_ENABLED="true")
        print("Teams 전송 켬(웹훅 값은 출력하지 않음)")
    else:
        print("Teams 전송 끔: harness/demo/reporting.local/teams.env 없음 또는 --no-delivery")
    if args.command == "test-send":
        subprocess.run(command, env=env, check=False)
        return
    subprocess.run(command + ["--bind", "127.0.0.1", "--port", str(PORT)], env=env, check=False)


if __name__ == "__main__":
    main()
