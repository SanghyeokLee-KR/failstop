# 로컬 DB 운영 실습

`run.py`의 기본 대상은 `failstop-ops-lab` 프로젝트다. 개발 DB나 AWS DB의 주소를 입력받지 않는다. 회원 가입과 실제 주문으로 체결을 만든 뒤, 앱을 멈추고 standby가 마지막 WAL까지 받은 것을 확인한다. 옛 primary 컨테이너의 정지를 확인한 다음 수동 승격하고 앱의 연결을 전환한다. 새 볼륨에 베이스백업으로 standby를 만들고, custom-format 백업을 별도 DB에 복원해 앱을 다시 기동한다.

서버의 현재 bootJar가 필요하다. Windows에서는 Docker 실행 파일이 PATH에 없으면 `DOCKER_BIN`에 실제 경로를 지정한다. Linux도 같은 Python 3 표준 라이브러리 도구를 쓴다.

```powershell
cd server
.\gradlew.bat :app:bootJar
cd ..
python deploy/lab/run.py build
python deploy/lab/run.py drill
python deploy/lab/run.py status
```

앱은 localhost:58180, 복원한 앱은 localhost:58182다. primary의 localhost:55440은 승격 이후 정지 상태로 남고, 새 primary는 localhost:55441이다. 비밀·백업·이미지 준비 파일은 Git에서 제외한 `.state/`에 둔다. 판정은 [실행 증거](../../artifacts/operations-20260918/db-recovery-drill.json)에 저장한다. 실패 시 자원을 보존하며 자동으로 이전 primary를 다시 켜지 않는다.

이미 승격된 기본 실습을 보존하고 다시 검증하려면 `python deploy/lab/run.py drill --repeat`를 실행한다. 매번 새 `failstop-ops-repeat-<임의 ID>` 프로젝트·볼륨·비밀과 빈 로컬 포트를 사용한다. 기존 고객 관측 대상에는 이 임시 프로젝트를 등록하지 않는다. 성공하면 이번 실행의 컨테이너·볼륨만 정리하고 `.state/<프로젝트>/exchange.dump`와 `artifacts/operations-20260918/<프로젝트>.json`을 보존한다. 실패하면 자원을 남겨 원인을 확인할 수 있게 한다. 원래 `failstop-ops-lab`과 이전 판정은 초기화하지 않는다.

판정은 거래·명령·이벤트·주문·원장이 모두 존재하는지, 전환 전후 건수가 같은지, 응답한 주문의 멱등 키가 각각 한 번 남았는지 확인한다. [기존 SQL 불변식](../../harness/sql/summary.sql)의 CRITICAL/HIGH 위반이 하나라도 있으면 실패한다. 조건부 불변식의 PASS만으로 모든 거래·복구 경로를 실행했다고 해석하지 않는다.

`drill --repeat`는 DB 전환·데이터 복원 검사이며 Teams 전달 검사가 아니다. 관측·보고 경로까지 확인할 때는 [등록된 standby 검사](../observability/README.md#실제-지표로-보고-연결을-검사한다)를 실행한다. 이 검사는 기존 고객 실습의 읽기 전용 replica를 잠시 중단·복구하고 실제 Prometheus 경보의 발화·해제와 보고 전송을 대조한다. 두 검사의 결과를 하나의 연속된 승격·보고 실험으로 합쳐 기록하지 않는다.

이 연습은 쓰기를 멈추고 복제를 따라잡게 한 통제된 전환이다. 갑작스러운 primary 소실의 RPO, 여러 호스트·AZ 장애나 AWS 성능을 증명하지 않는다. 기록된 앱 정상화 시간에는 운영자의 장애 판단 시간이 없다. 테스트 앱은 격리된 DB 관리자 계정을 사용한다. 실제 배포는 [역할별 계정과 마이그레이션 절차](../README.md)를 따른다.

종료할 때는 `python deploy/lab/run.py stop`을 실행한다. 처음부터 재실행하려면 증거와 백업을 확인한 뒤 다음 명령으로 **이 프로젝트의 볼륨만** 삭제한다.

```powershell
python deploy/lab/run.py destroy --confirm-project failstop-ops-lab
```

운영 백업 예약과 새 격리 DB의 복원 검증은 [백업 도구](../backup/README.md), 사람이 수행하는 실제 DB 전환은 [수동 페일오버 절차](../../docs/reference/operations/DB_수동_페일오버.md)를 따른다.
