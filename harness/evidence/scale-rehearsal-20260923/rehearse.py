# 시나리오 1 종단 리허설. 대본 단계를 그대로 따라가며 관리 API 와 조절기를 돌린다.
# 콘솔이 하는 일(희망 대수 변경, 발행률 변경)만 API 로 하고 컨테이너는 조절기가 움직인다.
import json
import subprocess
import sys
import time

SC = r"C:\Users\DEV-PC\AppData\Local\Temp\claude\C--Users-DEV-PC-Desktop-----\5aae4403-49b5-429a-9f08-31993fe2481d\scratchpad"
ADM = SC + r"\adm.sh"
BASH = "C:/Program Files/Git/bin/bash.exe"
T0 = time.time()
log = []


def say(kind, **kw):
    rec = {"t": round(time.time() - T0, 1), "kind": kind}
    rec.update(kw)
    log.append(rec)
    print("[%7.1fs] %-14s %s" % (rec["t"], kind, " ".join(f"{k}={v}" for k, v in kw.items())), flush=True)


def adm(method, path, body=None):
    # WSL bash 가 아니라 Git Bash 를 쓴다. 이름만 주면 PATH 에서 WSL 쪽이 먼저 잡힌다
    args = [BASH, ADM, method, path]
    if body is not None:
        args.append(json.dumps(body, ensure_ascii=False))
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=120)
    if p.returncode != 0:
        raise RuntimeError(f"{method} {path} 실패: {p.stderr.strip()[:200]}")
    try:
        return json.loads(p.stdout)
    except json.JSONDecodeError:
        raise RuntimeError(f"{method} {path} 응답 파싱 실패: {p.stdout[:200]}")


def cap():
    d = adm("GET", "/admin/v1/capacity")
    return {
        "observed": d["observed_total"],
        "desired": d["plan"]["desired"],
        "reconciled": d["reconciled"],
        "cpu": d["read_avg_cpu_ratio"],
        "advice": [a["id"] for a in d.get("advice", [])],
        "actions": [a["action"] for a in d.get("advice", []) if a.get("action")],
    }


def watch(label, until, budget, every=10):
    """until(cap) 이 참이 될 때까지 본다. 예산을 넘기면 그대로 적고 넘어간다."""
    end = time.time() + budget
    last = None
    while time.time() < end:
        c = cap()
        if c != last:
            say(label, **c)
            last = c
        if until(c):
            return c
        time.sleep(every)
    say(label + ".timeout", budget=budget, **cap())
    return cap()


pct = lambda c: "미수집" if c["cpu"] is None else f"{round(c['cpu'] * 100)}%"

say("start", **cap())

# 1단계. 기준 대수 확인
base = cap()
if not base["reconciled"]:
    say("baseline.mismatch", note="계약과 관측이 어긋난 상태로 시작한다")

# 2단계. 발행률 상향
r = adm("PUT", "/admin/v1/sim/rate", {"target_ops": "300"})
say("ops.set", target=300, apply=r.get("apply_path"))

# 3단계. 임계 도달
c = watch("breach", lambda c: "SCALE_OUT" in c["actions"], budget=240, every=10)
breached = "SCALE_OUT" in c["actions"]
say("breach.result", reached=breached, cpu=pct(c))

if breached:
    # 4단계. 희망 대수 상향
    adm("POST", "/admin/v1/capacity/scale-out", {"reason": "시나리오 1 리허설"})
    say("scale_out.requested")
    t_out = time.time()
    # 5단계. 증설 노드 등록
    c = watch("joined", lambda c: c["observed"] >= 3 and c["reconciled"], budget=180, every=5)
    say("joined.result", ok=(c["observed"] >= 3), elapsed=round(time.time() - t_out, 1), cpu=pct(c))
    # 6단계. 사용률 하락
    c = watch("spread", lambda c: "SCALE_OUT" not in c["actions"], budget=180, every=10)
    say("spread.result", ok=("SCALE_OUT" not in c["actions"]), cpu=pct(c))

# 7단계. 부하 원복과 축소
adm("PUT", "/admin/v1/sim/rate", {"target_ops": "10"})
say("ops.restore", target=10)
if cap()["desired"] > 2:
    adm("POST", "/admin/v1/capacity/scale-in", {"reason": "시나리오 1 리허설 종료"})
    say("scale_in.requested")
    t_in = time.time()
    c = watch("drained", lambda c: c["observed"] <= 2 and c["reconciled"], budget=180, every=5)
    say("drained.result", ok=(c["observed"] <= 2), elapsed=round(time.time() - t_in, 1))

say("stop", **cap())
with open(SC + r"\rehearse.jsonl", "w", encoding="utf-8") as f:
    for rec in log:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")
print("\n기록:", SC + r"\rehearse.jsonl")
