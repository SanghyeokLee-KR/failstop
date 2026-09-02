import csv, sys
p = sys.argv[1]
rows = list(csv.DictReader(open(p, encoding='utf-8-sig')))
def f(v):
    try: return float(v)
    except: return None
print(f"{'t':8} {'gate':5} {'pend':>5} {'act':>4} {'tmo':>5} {'queue':>6} {'inflO':>5} {'rej':>6} {'compl':>6} {'stand':>5} {'ep':>3} {'ldr':>3} {'jvm%':>6} {'pg%':>6} {'thr':>5}")
prev_rej = None; prev_tmo = None
for r in rows:
    rej = f(r['adm_rejected']); tmo = f(r['timeout_total'])
    drej = '' if prev_rej is None or rej is None else f"+{int(rej-prev_rej)}"
    dtmo = '' if prev_tmo is None or tmo is None else f"+{int(tmo-prev_tmo)}"
    prev_rej = rej if rej is not None else prev_rej; prev_tmo = tmo if tmo is not None else prev_tmo
    print(f"{r['t']:8} {r['gate']:5} {r['pending'][:5]:>5} {r['active'][:4]:>4} {dtmo:>5} {r['queue'][:6]:>6} {r['adm_inflight_order'][:5]:>5} {drej:>6} {r['completion_ratio'][:6]:>6} {r['standdown_total'][:5]:>5} {r['epoch'][:3]:>3} {r['is_leader'][:3]:>3} {r['jvm_cpu_pct'][:6]:>6} {r['pg_cpu_pct'][:6]:>6} {r['jvm_threads'][:5]:>5}")
