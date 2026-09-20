# 학교 AWS SSH 키 등록

2026-09-20 KST, 사용자의 관리자 안내에 따른 키페어 생성 요청으로 수행했다. 학교 계정 619256964117의 2026-ai-kopo-04 사용자로 실행했다. 다른 사용자의 EC2_TEST는 대상에서 제외했다.

AWS에서 RSA PEM 키페어 `2026-ai-kopo-04-ssh-01`(key-0ac302e384325e1b8)을 생성했다. 개인키는 PC의 사용자 전용 폴더에 저장하고 공개키만 서버로 보냈다. 같은 키를 로컬의 네트워크 차단 컨테이너에서 PuTTY PPK로 변환하고 공개키 일치를 검사했다. 개인키 생성·저장·변환 구간의 원문은 비밀정보이므로 캡처하지 않았다.

CLI 작업은 실제 명령과 원문 결과로 남겼으며 터미널 스크린샷으로 재구성하지 않았다. AWS 콘솔 키페어 목록은 실제 화면으로 캡처했다. 초기 직접 태그 추가는 CreateTags 명시적 거부, SSM SendCommand·DescribeInstanceInformation은 허용 부재로 실패했다. 초기 SSM 접속은 PC 플러그인 PATH 누락 상태에서 실패했고 종료 정리 시 TerminateSession 거부가 표시됐다. 설치된 플러그인의 경로를 추가한 뒤 StartInteractiveCommand와 StartSSHSession으로 정상 접속했다.

| 자료 | 내용 |
|---|---|
| `01_키페어_메타데이터.json` | 개인키 본문을 제거한 AWS 생성 응답 |
| `02_공개키등록_명령.json` | 기존 authorized_keys를 보존하는 공개키 추가·권한·sshd 검사 명령 |
| `03_SSM_접속시험.log` | 플러그인 경로 보완 전 접속 실패 |
| `04_SSM_플러그인경로_접속시험.log` | 경로 보완 후 ssm-user 접속 성공 |
| `05_SSM_공개키등록_요청.json` | sudo로 실행한 실제 등록 명령. 공개키만 포함 |
| 인스턴스 ID별 등록 로그 | 네 서버의 키 지문·파일 권한·서버 공개 호스트키 |
| `06_app_SSH_접속시험.log`와 ID별 SSH 로그 | 개인키 인증 후 ec2-user와 호스트명 확인 |
| `07_AWS_키페어_조회.json` | 키 ID와 username·group 자동 태그 |
| `08_AWS_키페어_화면.png`·`.txt` | AWS 키페어 목록의 실제 조회 화면·접근성 텍스트 |

앱 i-0465dd41a0390f262, DB i-0e302764af73d861f·i-099799f0dc164ae71, 봇 i-06ff9b16739362b14 모두 SSH 공개키 인증을 확인했다. 공개키 지문은 SHA256:aS9Smz1HUw59uMP5xpZhEVuOWG7CHnzeZbGg6RA31EQ다. 호스트키를 인증된 SSM 경로에서 수집한 뒤 SSH StrictHostKeyChecking으로 대조했다.

SG·서비스·DB 설정과 봇 가동은 변경하지 않았다. 성공한 경로는 SSM 경유 SSH이며 PuTTY GUI나 공인 IP 직접 접속 성공을 뜻하지 않는다. 접속 방법은 [AWS 작업 안내](../../docs/reports/AWS_작업.md#aws-키페어와-putty-접속-파일)에 있다.
