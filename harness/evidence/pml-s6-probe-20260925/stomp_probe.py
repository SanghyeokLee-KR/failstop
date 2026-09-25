"""ADR 0034 S6 브로커 확인용 STOMP 1.2 TCP 클라이언트(폐기용 컨테이너 안에서만 돈다).

외부 의존 없이 asyncio 로 CONNECT/SUBSCRIBE/SEND/MESSAGE 만 다룬다. 네 가지를 본다.

  bus        노드 단위 중계 모양. 발행 연결 둘이 한 목적지에 사용자 머리글을 단 메시지를 보내고, 구독 연결 둘이
             받는다. 머리글이 그대로 오는지, 발행자별 순서가 지켜지는지, 유실이 있는지 센다. --sub-ack client 이면
             설계가 채택한 구독(클라이언트 누적 확인, 선반입, 큐 인자)으로 받고 --ack-every·--ack-ms 마다 확인한다.
  perclient  세션 단위 중계 모양(Spring enableStompBrokerRelay). 연결 N 개가 각자 공개 토픽 12개와 사용자 큐
             둘을 구독하고 T 초 붙잡는다. 브로커 메모리를 따로 잰다.
  leftover   세션 단위 모양의 사용자 큐가 연결을 닫은 뒤 남는지 본다(auto-delete 머리글 유무 두 번).
  hold       연결 N 개를 CONNECT 만 하고 붙잡는다(구독 없음).
  alarm      메모리 경보 중 노드 단위 중계(설계 검토 대응). 발행 연결 하나가 데이터와 1초 하트비트 봉투를 보내고,
             쓰기 완료 대기가 상한을 넘으면 연결을 닫고(--close-mode) 재연결한다. 구독 연결 하나가 채택한 구독으로
             받는다. 경보는 바깥에서 rabbitmqctl 로 켜고 끈다. 연결 세대별 수신, 하트비트 공백, 닫은 연결의 늦은 전달을 센다.

결과는 한 줄 JSON 으로 표준 출력에 낸다.
"""
import argparse
import asyncio
import json
import socket
import struct
import sys
import time

NUL = b"\x00"


def frame(command, headers, body=b""):
    lines = [command]
    for k, v in headers.items():
        lines.append(f"{k}:{v}")
    return ("\n".join(lines) + "\n\n").encode() + body + NUL


class Conn:
    def __init__(self, reader, writer):
        self.r = reader
        self.w = writer
        self.buf = b""

    @classmethod
    async def open(cls, host, port, login, passcode, vhost, heartbeat="0,0"):
        reader, writer = await asyncio.open_connection(host, port)
        c = cls(reader, writer)
        hdr = {"accept-version": "1.2", "host": vhost, "login": login, "passcode": passcode,
               "heart-beat": heartbeat}
        writer.write(frame("CONNECT", hdr))
        await writer.drain()
        f = await c.next_frame(timeout=10)
        if f is None or f[0] != "CONNECTED":
            raise RuntimeError(f"CONNECT 실패: {f}")
        return c

    async def next_frame(self, timeout=None):
        while True:
            # 앞쪽 개행(하트비트)을 버린다.
            while self.buf.startswith(b"\n") or self.buf.startswith(b"\r\n"):
                self.buf = self.buf[1:]
            idx = self.buf.find(NUL)
            if idx >= 0:
                raw = self.buf[:idx]
                self.buf = self.buf[idx + 1:]
                head, _, body = raw.partition(b"\n\n")
                lines = head.decode("utf-8", "replace").split("\n")
                cmd = lines[0].strip()
                headers = {}
                for line in lines[1:]:
                    if ":" in line:
                        k, v = line.split(":", 1)
                        headers.setdefault(k, v)
                return cmd, headers, body
            try:
                chunk = await asyncio.wait_for(self.r.read(65536), timeout=timeout)
            except asyncio.TimeoutError:
                return None
            if not chunk:
                return None
            self.buf += chunk

    async def send(self, command, headers, body=b""):
        self.w.write(frame(command, headers, body))
        await self.w.drain()

    async def close(self):
        try:
            await self.send("DISCONNECT", {"receipt": "bye"})
            await self.next_frame(timeout=2)
        except Exception:
            pass
        self.w.close()


MARKETS = ["KRW-BTC", "KRW-ETH", "KRW-SOL", "KRW-XRP", "KRW-DOGE", "KRW-ADA",
           "KRW-TRX", "KRW-LINK", "KRW-DOT", "KRW-MATIC", "KRW-AVAX", "KRW-ATOM"]


def subscribe_headers(a, sub_id):
    """설계가 채택한 구독 머리글. --sub-ack auto 이면 종전 확인(R1·R4)과 같은 자동 확인이다."""
    h = {"id": sub_id, "destination": a.dest, "ack": a.sub_ack}
    if a.sub_prefetch > 0:
        h["prefetch-count"] = str(a.sub_prefetch)
    if a.sub_max_length > 0:
        h["x-max-length"] = str(a.sub_max_length)
    if a.sub_max_length_bytes > 0:
        h["x-max-length-bytes"] = str(a.sub_max_length_bytes)
    if a.sub_ttl_ms > 0:
        h["x-message-ttl"] = str(a.sub_ttl_ms)
    return h


async def drain_subscriber(s, a, expected):
    """구독 연결 하나를 발행과 동시에 읽는다. 노드의 구독자는 늘 읽고 있으므로 이 모양이 실제와 같다.

    클라이언트 확인이면 마지막으로 받은 메시지의 ack 값으로 누적 확인을 --ack-every 건이나 --ack-ms 마다 보낸다.
    """
    got = {"node-0": [], "node-1": []}
    header_ok = 0
    header_missing = []
    first_at = None
    last_at = None
    acks_sent = 0
    pending_ack = None
    since_ack = 0
    last_ack_at = time.time()
    deadline = time.time() + a.wait
    while time.time() < deadline and (len(got["node-0"]) + len(got["node-1"])) < expected:
        f = await s.next_frame(timeout=max(0.05, min(a.ack_ms / 1000.0, deadline - time.time())))
        now = time.time()
        if f is None:
            if s.r.at_eof():
                break
        else:
            cmd, h, body = f
            if cmd == "MESSAGE":
                first_at = first_at or now
                last_at = now
                origin = h.get("x-origin")
                if origin in got:
                    got[origin].append(int(h.get("x-seq", "0")))
                if all(k in h for k in ("x-origin", "x-dest", "x-user", "x-kind", "x-seq")):
                    header_ok += 1
                elif len(header_missing) < 3:
                    header_missing.append(sorted(h.keys()))
                if a.sub_ack != "auto":
                    pending_ack = h.get("ack")
                    since_ack += 1
        if pending_ack and (since_ack >= a.ack_every or (now - last_ack_at) * 1000.0 >= a.ack_ms):
            await s.send("ACK", {"id": pending_ack})
            acks_sent += 1
            pending_ack = None
            since_ack = 0
            last_ack_at = now
    if pending_ack:
        await s.send("ACK", {"id": pending_ack})
        acks_sent += 1
    ordered = {k: v == sorted(v) for k, v in got.items()}
    return {
        "received": {k: len(v) for k, v in got.items()},
        "in_publisher_order": ordered,
        "custom_headers_intact": header_ok,
        "sample_missing_headers": header_missing,
        "receive_span_seconds": round((last_at or 0) - (first_at or 0), 3),
        "acks_sent": acks_sent,
    }


async def run_bus(a):
    subs = [await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost) for _ in range(2)]
    for i, s in enumerate(subs):
        await s.send("SUBSCRIBE", subscribe_headers(a, f"bus-{i}"))
    await asyncio.sleep(1.0)
    readers = [asyncio.create_task(drain_subscriber(s, a, 2 * a.count)) for s in subs]
    pubs = [await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost) for _ in range(2)]
    t0 = time.time()
    for n in range(a.count):
        for p_i, p in enumerate(pubs):
            body = json.dumps({"type": "orderbook", "market": MARKETS[n % 12], "seq": n + 1,
                               "epoch": 7}).encode()
            await p.send("SEND", {"destination": a.dest, "content-type": "application/json",
                                   "x-origin": f"node-{p_i}", "x-dest": f"/topic/orderbook.{MARKETS[n % 12]}",
                                   "x-user": "12345", "x-kind": "msg", "x-seq": str(n + 1)}, body)
        if a.rate > 0:
            await asyncio.sleep(1.0 / a.rate)
    pub_s = time.time() - t0
    result = {"mode": "bus", "count_per_publisher": a.count, "rate_per_publisher": a.rate,
              "subscribe": subscribe_headers(a, "bus-n"), "ack_every": a.ack_every, "ack_ms": a.ack_ms,
              "publish_seconds": round(pub_s, 3), "subscribers": await asyncio.gather(*readers)}
    for c in subs + pubs:
        await c.close()
    print(json.dumps(result, ensure_ascii=False))


async def hold_perclient(a):
    conns = []
    t0 = time.time()
    errors = 0
    for k in range(a.n):
        try:
            c = await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost)
        except Exception as e:  # noqa: BLE001
            errors += 1
            if errors < 3:
                print(f"연결 실패: {e}", file=sys.stderr)
            continue
        if a.mode == "perclient":
            for i, m in enumerate(MARKETS):
                await c.send("SUBSCRIBE", {"id": f"t{i}", "destination": f"/topic/orderbook.{m}"})
            extra = {}
            if a.auto_delete:
                extra = {"auto-delete": "true", "durable": "false"}
            for j, q in enumerate(("myOrder", "myAsset")):
                h = {"id": f"q{j}", "destination": f"/queue/{q}-user{a.tag}{k}"}
                h.update(extra)
                await c.send("SUBSCRIBE", h)
        conns.append(c)
    setup_s = time.time() - t0
    print(json.dumps({"mode": a.mode, "requested": a.n, "open": len(conns), "errors": errors,
                      "setup_seconds": round(setup_s, 2), "hold_seconds": a.hold,
                      "auto_delete": a.auto_delete}, ensure_ascii=False), flush=True)
    await asyncio.sleep(a.hold)
    for c in conns:
        await c.close()
    print(json.dumps({"closed": len(conns)}), flush=True)


async def run_stall(a):
    """멈춘 구독자 흉내. 큐 인자(x-max-length, x-message-ttl)를 단 구독을 하고 읽지 않는다.

    발행자가 보내는 동안 이 연결은 한 프레임도 읽지 않는다. 브로커가 큐를 인자대로 자르는지는 바깥에서
    rabbitmqctl 로 본다. --hb 가 0 이 아니면 하트비트를 협상만 하고 보내지 않아 브로커가 끊는지 본다.
    """
    hb = f"{a.hb},{a.hb}" if a.hb > 0 else "0,0"
    stalled = await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost, heartbeat=hb)
    h = {"id": "stall", "destination": a.dest, "ack": a.ack}
    if a.prefetch > 0:
        h["prefetch-count"] = str(a.prefetch)
    if a.max_length > 0:
        h["x-max-length"] = str(a.max_length)
    if a.max_length_bytes > 0:
        h["x-max-length-bytes"] = str(a.max_length_bytes)
    if a.ttl_ms > 0:
        h["x-message-ttl"] = str(a.ttl_ms)
    await stalled.send("SUBSCRIBE", h)
    await asyncio.sleep(1.0)
    pub = await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost)
    for n in range(a.count):
        body = json.dumps({"type": "orderbook", "seq": n + 1, "pad": "x" * a.stall_pad}).encode()
        await pub.send("SEND", {"destination": a.dest, "x-origin": "node-0", "x-seq": str(n + 1)}, body)
    await pub.close()
    print(json.dumps({"mode": "stall", "published": a.count, "x-max-length": a.max_length,
                      "x-max-length-bytes": a.max_length_bytes, "x-message-ttl": a.ttl_ms, "heartbeat": hb,
                      "ack": a.ack, "prefetch": a.prefetch, "pad": a.stall_pad}), flush=True)
    # 읽지 않고 붙잡는다. 브로커가 하트비트 부재로 끊으면 소켓이 닫힌다.
    t0 = time.time()
    closed_after = None
    while time.time() - t0 < a.hold:
        await asyncio.sleep(1.0)
        if stalled.r.at_eof():
            closed_after = round(time.time() - t0, 1)
            break
    print(json.dumps({"broker_closed_after_seconds": closed_after, "held": round(time.time() - t0, 1)}), flush=True)


async def run_silent(a):
    """하트비트를 협상하고 읽기만 하며 한 바이트도 쓰지 않는다. 브로커가 언제 끊는지 잰다(멈춘 노드 흉내)."""
    hb = f"{a.hb},{a.hb}"
    c = await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost, heartbeat=hb)
    await c.send("SUBSCRIBE", {"id": "silent", "destination": a.dest, "ack": "auto"})
    t0 = time.time()
    closed_after = None
    while time.time() - t0 < a.hold:
        try:
            chunk = await asyncio.wait_for(c.r.read(65536), timeout=1.0)
        except asyncio.TimeoutError:
            continue
        if not chunk:
            closed_after = round(time.time() - t0, 1)
            break
    print(json.dumps({"mode": "silent", "heartbeat": hb, "broker_closed_after_seconds": closed_after}), flush=True)


async def try_write(conn, data, timeout):
    """쓰고 전송 버퍼가 빌 때까지 timeout 초 기다린다. 제때 비면 참."""
    conn.w.write(data)
    try:
        await asyncio.wait_for(conn.w.drain(), timeout=timeout)
        return True
    except asyncio.TimeoutError:
        return False


async def run_alarm(a):
    """메모리 경보 중 노드 단위 중계(설계 검토 대응 R11).

    구독 연결 하나(하트비트 --hb, 채택한 구독)와 발행 연결 하나(같은 하트비트)를 연다. 발행 쪽은 초당 --rate 건의
    데이터 봉투와 --hb-interval 초마다 하트비트 봉투(x-kind=hb)를 한 연결로 보낸다. 쓰기마다 전송 버퍼가 비기를
    --write-timeout 초까지 기다리고(asyncio 쓰기 버퍼 상한 0으로 Netty 쓰기 완료 대기를 흉내 낸다), 넘으면 그 연결을
    --close-mode 로 닫는다. rst 는 SO_LINGER 0 으로 곧바로 끊고, graceful 은 커널이 남은 바이트를 계속 보내게 둔다.
    재연결 간격은 1초에서 두 배씩 10초까지이고 5초 넘게 정상이던 연결 뒤에는 1초로 돌린다. 연결마다 세대(x-gen)를
    올린다. 경보는 이 스크립트 밖(브로커 컨테이너 안의 rabbitmqctl)에서 켜고 끈다.
    """
    t0 = time.time()
    events = []

    def ev(name, **kw):
        e = {"t": round(time.time() - t0, 3), "wall": round(time.time(), 3), "event": name}
        e.update(kw)
        events.append(e)

    hb = f"{a.hb},{a.hb}" if a.hb > 0 else "0,0"
    sub = await Conn.open(a.host, a.port, a.login, a.passcode, a.vhost, heartbeat=hb)
    await sub.send("SUBSCRIBE", subscribe_headers(a, "bus"))
    ev("sub_subscribed")

    stop = asyncio.Event()
    per_gen = {}
    per_sec = {}
    hb_recv = []
    state = {"max_gen_seen": 0, "inversions": 0, "sub_closed_after": None, "sub_error": None,
             "acks": 0, "sub_write_blocked": 0}
    closed_at = {}
    after_close = {}

    async def sub_reader():
        pending_ack = None
        since_ack = 0
        last_ack_at = time.time()
        last_write = time.time()
        while not stop.is_set():
            f = await sub.next_frame(timeout=0.2)
            now = time.time()
            rel = now - t0
            if f is None:
                if sub.r.at_eof():
                    state["sub_closed_after"] = round(rel, 3)
                    ev("sub_closed")
                    return
            else:
                cmd, h, body = f
                if cmd == "MESSAGE":
                    gen = int(h.get("x-gen", "0"))
                    seq = int(h.get("x-seq", "0"))
                    kind = h.get("x-kind", "?")
                    age = now - float(h.get("x-sent-at", "0"))
                    g = per_gen.setdefault(gen, {"received": 0, "hb": 0, "first_recv": round(rel, 3),
                                                 "last_recv": 0.0, "max_age": 0.0, "min_seq": seq, "max_seq": seq})
                    g["received"] += 1
                    g["last_recv"] = round(rel, 3)
                    g["max_age"] = round(max(g["max_age"], age), 3)
                    g["min_seq"] = min(g["min_seq"], seq)
                    g["max_seq"] = max(g["max_seq"], seq)
                    if gen < state["max_gen_seen"]:
                        state["inversions"] += 1
                    state["max_gen_seen"] = max(state["max_gen_seen"], gen)
                    if gen in closed_at and rel > closed_at[gen]:
                        after_close[gen] = after_close.get(gen, 0) + 1
                    bucket = per_sec.setdefault(int(rel), [0, 0])
                    if kind == "hb":
                        g["hb"] += 1
                        bucket[1] += 1
                        hb_recv.append((round(rel, 3), gen, round(age, 3)))
                    else:
                        bucket[0] += 1
                    if a.sub_ack != "auto":
                        pending_ack = h.get("ack")
                        since_ack += 1
                elif cmd == "ERROR":
                    state["sub_error"] = {"headers": h, "body": body[:200].decode("utf-8", "replace")}
                    ev("sub_error")
            if pending_ack and (since_ack >= a.ack_every or (now - last_ack_at) * 1000.0 >= a.ack_ms):
                if not await try_write(sub, frame("ACK", {"id": pending_ack}), 1.0):
                    state["sub_write_blocked"] += 1
                    ev("sub_ack_write_blocked")
                state["acks"] += 1
                pending_ack = None
                since_ack = 0
                last_ack_at = now
                last_write = now
            if a.hb > 0 and (now - last_write) * 1000.0 >= a.hb * 0.8:
                if not await try_write(sub, b"\n", 1.0):
                    state["sub_write_blocked"] += 1
                    ev("sub_heartbeat_write_blocked")
                last_write = now

    pub_gens = {}

    async def pub_loop():
        gen = 0
        seq = 0
        backoff = 1.0
        body = json.dumps({"type": "orderbook", "pad": "x" * max(0, a.body_bytes - 40)}).encode()
        interval = 1.0 / a.rate if a.rate > 0 else 0.0
        while not stop.is_set():
            gen += 1
            try:
                pub = await asyncio.wait_for(
                    Conn.open(a.host, a.port, a.login, a.passcode, a.vhost, heartbeat=hb), timeout=5)
            except Exception as e:  # noqa: BLE001
                ev("pub_connect_fail", gen=gen, err=f"{type(e).__name__}: {str(e)[:100]}")
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 10.0)
                continue
            pub.w.transport.set_write_buffer_limits(high=0)
            gs = {"connected": round(time.time() - t0, 3), "sent": 0, "first_seq": seq + 1, "last_seq": None,
                  "closed": None, "reason": None, "unsent_bytes": 0}
            pub_gens[gen] = gs
            ev("pub_connected", gen=gen)
            next_hb = time.time()
            next_send = time.time()
            reason = None
            try:
                while not stop.is_set():
                    now = time.time()
                    kind = "data"
                    if now >= next_hb:
                        kind = "hb"
                        next_hb = now + a.hb_interval
                    seq += 1
                    hdr = {"destination": a.dest, "content-type": "application/json", "x-origin": "node-0",
                           "x-gen": str(gen), "x-seq": str(seq), "x-kind": kind, "x-sent-at": f"{now:.3f}"}
                    if not await try_write(pub, frame("SEND", hdr, body if kind == "data" else b"{}"),
                                           a.write_timeout):
                        reason = "write_timeout"
                        gs["unsent_bytes"] = pub.w.transport.get_write_buffer_size()
                        ev("pub_write_timeout", gen=gen, seq=seq)
                        break
                    gs["sent"] += 1
                    gs["last_seq"] = seq
                    if pub.r.at_eof():
                        reason = "eof"
                        break
                    next_send += interval
                    delay = next_send - time.time()
                    if delay > 0:
                        await asyncio.sleep(delay)
                    else:
                        next_send = time.time()
            except Exception as e:  # noqa: BLE001
                reason = f"error:{type(e).__name__}"
            gs["closed"] = round(time.time() - t0, 3)
            gs["reason"] = reason or "stop"
            closed_at[gen] = gs["closed"]
            if a.close_mode == "rst":
                sock = pub.w.get_extra_info("socket")
                try:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                except Exception:  # noqa: BLE001
                    pass
                pub.w.transport.abort()
            else:
                pub.w.transport.close()
            ev("pub_closed", gen=gen, reason=gs["reason"], mode=a.close_mode)
            if stop.is_set():
                break
            if gs["closed"] - gs["connected"] >= 5.0 and gs["sent"] > 0:
                backoff = 1.0
            ev("pub_backoff", gen=gen, seconds=backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 10.0)

    reader = asyncio.create_task(sub_reader())
    writer = asyncio.create_task(pub_loop())
    await asyncio.sleep(a.duration)
    stop.set()
    await asyncio.gather(reader, writer, return_exceptions=True)
    gaps = []
    for (t_prev, _, _), (t_next, g_next, age_next) in zip(hb_recv, hb_recv[1:]):
        gaps.append({"from": t_prev, "to": t_next, "gap": round(t_next - t_prev, 3), "gen_next": g_next,
                     "age_next": age_next})
    gaps.sort(key=lambda x: -x["gap"])
    result = {
        "mode": "alarm", "started_wall": round(t0, 3), "heartbeat": hb, "rate": a.rate, "body_bytes": a.body_bytes,
        "hb_interval": a.hb_interval, "write_timeout": a.write_timeout, "close_mode": a.close_mode,
        "subscribe": subscribe_headers(a, "bus"), "ack_every": a.ack_every, "ack_ms": a.ack_ms,
        "duration": a.duration, "sub": state, "pub_gens": pub_gens, "recv_per_gen": per_gen,
        "received_after_close": after_close, "hb_gaps_top": gaps[:5],
        "per_second": [[s] + per_sec[s] for s in sorted(per_sec)], "events": events,
    }
    try:
        await sub.close()
    except Exception:  # noqa: BLE001
        pass
    print(json.dumps(result, ensure_ascii=False))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("mode", choices=["bus", "perclient", "hold", "leftover", "stall", "silent", "alarm"])
    # 채택한 구독(설계 4-2). 기본값은 종전 확인(R1·R4)의 자동 확인이다.
    p.add_argument("--sub-ack", default="auto")
    p.add_argument("--sub-prefetch", type=int, default=0)
    p.add_argument("--sub-max-length", type=int, default=0)
    p.add_argument("--sub-max-length-bytes", type=int, default=0)
    p.add_argument("--sub-ttl-ms", type=int, default=0)
    p.add_argument("--ack-every", type=int, default=200)
    p.add_argument("--ack-ms", type=float, default=200.0)
    p.add_argument("--body-bytes", type=int, default=1000)
    p.add_argument("--hb-interval", type=float, default=1.0)
    p.add_argument("--write-timeout", type=float, default=1.0)
    p.add_argument("--close-mode", choices=["rst", "graceful"], default="rst")
    p.add_argument("--duration", type=float, default=70.0)
    p.add_argument("--max-length", type=int, default=0)
    p.add_argument("--max-length-bytes", type=int, default=0)
    # stall 모드 본문 채움 길이. R6·R6b·R10 은 400 이다.
    p.add_argument("--stall-pad", type=int, default=400)
    p.add_argument("--ttl-ms", type=int, default=0)
    p.add_argument("--hb", type=int, default=0)
    p.add_argument("--ack", default="auto")
    p.add_argument("--prefetch", type=int, default=0)
    p.add_argument("--host", default="broker")
    p.add_argument("--port", type=int, default=61613)
    p.add_argument("--login", default="probe")
    p.add_argument("--passcode", default="probe")
    p.add_argument("--vhost", default="/")
    p.add_argument("--dest", default="/topic/exch.bus.v1")
    p.add_argument("--count", type=int, default=5000)
    # 발행자 하나의 초당 메시지 수. 0 이면 쉬지 않고 보낸다(버스트).
    p.add_argument("--rate", type=float, default=0.0)
    p.add_argument("--wait", type=float, default=20.0)
    p.add_argument("--n", type=int, default=100)
    p.add_argument("--hold", type=float, default=30.0)
    p.add_argument("--auto-delete", action="store_true")
    p.add_argument("--tag", default="s")
    a = p.parse_args()
    if a.mode == "bus":
        asyncio.run(run_bus(a))
    elif a.mode == "stall":
        asyncio.run(run_stall(a))
    elif a.mode == "silent":
        asyncio.run(run_silent(a))
    elif a.mode == "alarm":
        asyncio.run(run_alarm(a))
    elif a.mode == "leftover":
        a.mode = "perclient"
        a.hold = 1.0
        asyncio.run(hold_perclient(a))
    else:
        asyncio.run(hold_perclient(a))


if __name__ == "__main__":
    main()
