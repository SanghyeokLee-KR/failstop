"""검증한 공통 보고서 조회를 학교 AWS로 제한한 설정 파일을 만든다. 배포·전송은 하지 않는다."""
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / 'deploy/reporting'))
from scope import scoped_config

config = scoped_config(json.loads((root / 'deploy/reporting/config.example.json').read_text(encoding='utf-8')), 'exchange', 'aws')
config.update(analyzer='facts-only', prometheus_url='http://127.0.0.1:9090',
              alertmanager_url='http://127.0.0.1:9093')
config['artifacts'] = {
    'enabled': True, 'public_base_url': 'https://www.sang.it.kr/operations',
    'directory': '/var/lib/failstop-reporting/artifacts', 'ttl_seconds': 604800,
    'font': '/usr/share/fonts/nanum/NanumGothic.ttf',
    'font_bold': '/usr/share/fonts/nanum/NanumGothicBold.ttf',
}
destination = Path(__file__).with_name('config.school.json')
destination.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print('학교 AWS 보고 설정 생성. 실제 배포·외부 전송은 미실행.')
