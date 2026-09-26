== R8 메모리 경보에서 발행 연결 막힘 03:34:00Z
set_vm_memory_high_watermark absolute 50MiB 로 경보를 켜고 bus 모드(발행 2x20000)를 40초 제한으로 돌렸다.
결과: 스크립트가 40초 안에 끝나지 않았다(timeout 종료 코드 124). 발행 쓰기가 막혔고 ERROR 프레임은 오지 않았다.
되돌림: absolute 192MiB 로 되돌린 뒤 경보 없음.
