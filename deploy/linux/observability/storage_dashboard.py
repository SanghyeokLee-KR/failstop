"""학교 EC2의 루트 파일시스템과 PostgreSQL 크기를 비교하는 화면."""
import copy
import json

DS = {"type": "prometheus", "uid": "prometheus"}
HOST = 'customer="$customer",environment="$environment",job=~"linux-host|linux",node=~"$node"'
FS = HOST + ',mountpoint="/",fstype=~"xfs|ext4"'
DB = 'customer="$customer",environment="$environment",job="postgres",node=~"$node"'


def observed(expr, selector):
    return f'({expr}) and on(instance,job) ((up{{{selector}}} == 1) and (time() - timestamp(up{{{selector}}}) < 90))'


def build_storage(reference):
    d = copy.deepcopy(reference)
    d.update(uid="msp-storage", title="07 · 디스크·용량", id=None,
             description="4대 EC2의 실제 루트 파일시스템 여유와 DB·WAL 크기. 자동 삭제 기능은 없다.",
             panels=[])
    d["templating"]["list"].append({
        "name": "node", "label": "서버", "type": "query", "datasource": DS,
        "query": 'label_values(up{customer="$customer",environment="$environment",job=~"linux-host|linux"}, node)',
        "refresh": 1, "multi": True, "includeAll": True, "allValue": ".*",
        "current": {"text": "All", "value": "$__all"}, "options": [],
    })
    size = observed(f'node_filesystem_size_bytes{{{FS}}}', HOST)
    avail = observed(f'node_filesystem_avail_bytes{{{FS}}}', HOST)
    used = f'100 * (1 - ({avail}) / ({size}))'
    inode = observed(f'100 * (1 - node_filesystem_files_free{{{FS}}} / node_filesystem_files{{{FS}}})', HOST)

    def panel(title, kind, pos, expressions, unit="bytes", thresholds=None):
        i = len(d["panels"]) + 1
        p = {"id": i, "title": title, "type": kind, "gridPos": dict(zip(("x","y","w","h"),pos)),
             "datasource": DS, "fieldConfig": {"defaults": {"unit": unit, "noValue": "미수집", "decimals": 1,
             "color": {"mode": "thresholds" if thresholds else "palette-classic"},
             "thresholds": {"mode": "absolute", "steps": thresholds or [{"color":"green","value":None}]},
             "mappings": [{"type":"special","options":{"match":"null+nan","result":{"text":"미수집","color":"text"}}}]}, "overrides": []},
             "targets": [{"refId": chr(65+j), "expr": expr, "legendFormat": "{{node}}", "instant": kind in ("stat","bargauge"), "range": kind=="timeseries"}
                         for j,expr in enumerate(expressions)]}
        if kind == "stat":
            p["options"]={"colorMode":"value","graphMode":"none","textMode":"value","justifyMode":"left","reduceOptions":{"calcs":["lastNotNull"],"fields":"","values":False}}
        elif kind == "bargauge":
            p["fieldConfig"]["defaults"]["min"] = 0
            p["options"]={"orientation":"horizontal","displayMode":"gradient","showUnfilled":True,"reduceOptions":{"calcs":["lastNotNull"],"fields":"","values":False}}
        else:
            p["fieldConfig"]["defaults"]["color"] = {"mode":"palette-classic"}
            p["options"]={"legend":{"displayMode":"list","placement":"bottom"},"tooltip":{"mode":"multi"}}
        d["panels"].append(p)
        return p

    limits=[{"color":"green","value":None},{"color":"yellow","value":70},{"color":"orange","value":85},{"color":"red","value":95}]
    panel("디스크 사용률 · 최대", "stat", (0,0,6,3), [f'max({used})'], "percent", limits)
    panel("남은 용량 · 최소", "stat", (6,0,6,3), [f'min({avail})'])
    panel("inode 사용률 · 최대", "stat", (12,0,6,3), [f'max({inode})'], "percent", limits)
    p=panel("호스트 수집 · 정상 / 등록", "stat", (18,0,6,3),
            [f'sum(up{{{HOST}}})',f'count(up{{{HOST}}})'], "short")
    p["options"]["textMode"]="value_and_name"
    p["fieldConfig"]["defaults"].update(decimals=0,color={"mode":"fixed","fixedColor":"blue"})
    p["targets"][0]["legendFormat"]="정상"; p["targets"][1]["legendFormat"]="등록"
    for title,x,expr,unit in [("서버별 사용률",0,used,"percent"),("서버별 남은 용량",8,avail,"bytes"),("파일시스템 크기",16,size,"bytes")]:
        p=panel(title,"bargauge",(x,3,8,5),[f'max by(node) ({expr})'],unit,limits if unit=="percent" else None)
        if unit=="percent":p["fieldConfig"]["defaults"].update(min=0,max=100)
    panel("남은 용량 추이", "timeseries", (0,8,12,6), [f'max by(node) ({avail})'])
    panel("inode 사용률 추이", "timeseries", (12,8,12,6), [f'max by(node) ({inode})'], "percent", limits)
    panel("PostgreSQL · exchange DB 크기", "timeseries", (0,14,12,5),
          [observed(f'pg_database_size_bytes{{{DB},datname="exchange"}}',DB)])
    panel("PostgreSQL · WAL 디렉터리 크기", "timeseries", (12,14,12,5),
          [observed(f'pg_wal_position_dir_bytes{{{DB}}}',DB)])
    d["panels"].append({"id":12,"title":"용량이 늘 때 확인할 순서","type":"text","gridPos":{"x":0,"y":19,"w":24,"h":3},
       "options":{"mode":"markdown","content":"**70% 주의 · 85% 조치 · 95% 긴급**은 화면 기준입니다. 자동 삭제나 Teams 경보는 연결하지 않았습니다.  \
**로그·캐시·오래된 백업 확인 → 보존 기준과 복원 가능 여부 확인 → 승인된 파일만 정리.** DB 데이터와 `pg_wal`은 직접 지우지 않습니다.  \
두 DB의 50GiB는 각각의 볼륨입니다. 복제 때문에 저장 공간이 합쳐지지 않으며, OS·로그·WAL도 같은 디스크를 사용합니다."}})
    return d
