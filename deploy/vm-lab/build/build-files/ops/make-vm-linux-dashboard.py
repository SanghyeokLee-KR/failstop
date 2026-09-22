"""학교 AWS 용 Linux 화면을 OPS01 의 VM 라벨에 맞춘 사본으로 바꾼다.

원본은 failstop/deploy/linux/observability/linux-dashboard.json 이며 이 폴더에 같은 이름으로 복사해 두었다.
원본을 그대로 두면 OPS01 에서 패널이 전부 비어 보인다. 원본의 모든 식이 job="linux" 를 고르는데
VM 의 prometheus.vm.yml 에는 그 작업 이름이 없기 때문이다. VM 은 여섯 대를 모두 job="linux-host" 로
수집한다. 그래서 다음 네 가지만 바꾼다. 식의 구조와 임계값, 패널 배치는 건드리지 않는다.

  1. 식과 변수의 job="linux" -> job="linux-host"
  2. uid school-linux -> vm-linux (학교 화면과 uid 가 겹치지 않게 한다)
  3. 제목 "학교 AWS · Linux 운영" -> "VM 실습 · Linux 운영"
  4. 태그 school-aws -> vm-lab, id 는 비운다(프로비저닝 충돌 방지)

이 화면은 EDGE01 과 OPS01 을 볼 수 있는 유일한 화면이다. MSP 화면 7개는 대부분의 패널이
role=~"app|reader|data|standby|probe" 로 대상을 좁히므로 role 이 edge 와 observability 인 두 대는 나오지 않는다.

사용법(작업 PC 또는 OPS01 어디서 실행해도 결과가 같다):
  python3 make-vm-linux-dashboard.py --source linux-dashboard.json --output linux-dashboard.vm.json
"""
import argparse
import json
from pathlib import Path


def convert(source: Path, output: Path) -> None:
    text = source.read_text(encoding="utf-8")
    # 식은 JSON 문자열 안에 있으므로 문자열 치환으로 바꾼다. job="linux" 만 정확히 고른다.
    replaced = text.replace('job=\\"linux\\"', 'job=\\"linux-host\\"')
    replaced = replaced.replace('job="linux"', 'job="linux-host"')
    dashboard = json.loads(replaced)

    dashboard["uid"] = "vm-linux"
    dashboard["title"] = "VM 실습 · Linux 운영"
    dashboard["id"] = None
    tags = [tag for tag in dashboard.get("tags", []) if tag != "school-aws"]
    if "vm-lab" not in tags:
        tags.append("vm-lab")
    dashboard["tags"] = tags
    dashboard["description"] = (
        "VM 실습 환경 배포. 학교 AWS 의 Linux 화면을 job=linux-host 로 옮긴 것이다. "
        "여섯 VM 이 모두 여기에 나온다. 미수집은 정상 판정이 아니다."
    )

    output.write_text(
        json.dumps(dashboard, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # 식은 JSON 문자열 안에 있어 따옴표가 \" 로 저장된다. 그 모양 그대로 센다.
    saved = output.read_text(encoding="utf-8")
    host_count = saved.count('job=\\"linux-host\\"')
    left_over = saved.count('job=\\"linux\\"')
    print("uid", dashboard["uid"], "| 제목", dashboard["title"])
    print('job="linux-host" 로 바뀐 자리', host_count)
    print('job="linux" 로 남은 자리', left_over, "(0 이어야 한다)")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    convert(arguments.source, arguments.output)


if __name__ == "__main__":
    main()
