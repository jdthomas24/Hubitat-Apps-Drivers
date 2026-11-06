import json, sys, re, os, collections
src, outdir = sys.argv[1], sys.argv[2]
raw = json.load(open(src + "/hms_en.json")); wiki = json.load(open(src + "/wiki_links.json"))
def text(v):
    xs = [x for x in (v or {}).keys() if x]
    return xs[0] if xs else None
FAM = {"07": "AMS ", "18": "AMS-HT ", "12": "AMS Lite "}
def fam(code):
    p = code[:2]
    if p in FAM and code[2:4] in [f"{n:02X}" for n in range(8)]: return p
    return None
def under(c): return "_".join(c[i:i+4] for i in range(0, 16, 4)) if len(c) == 16 else c
def seg(code, path):
    m = re.match(r"^/en/([a-z0-9-]+)/troubleshooting/hmscode/(.+)$", path)
    if m and m.group(2).replace("-", "_").upper() == under(code).upper():
        return m.group(1) if "-" not in m.group(2) else m.group(1) + "|-"
    return path
def links(code):
    e = wiki.get(code) or {}
    d = h = None
    for p, models in e.items():
        if not models: d = seg(code, p)
        elif any(m.startswith("H2") for m in models): h = seg(code, p)
    return d, h
files = collections.defaultdict(dict); dropped = 0
for code, v in raw["device_hms"].items():
    t = text(v)
    if not t: continue
    f = fam(code); fname = (f + "00") if f else code[:4]
    if f and code[2:4] != "00":
        base = code[:2] + "00" + code[4:]; bt = text(raw["device_hms"].get(base)); n = int(code[2:4], 16)
        if bt and bt.replace(FAM[f] + "A", FAM[f] + chr(65 + n), 1) == t: dropped += 1; continue
    d, h = links(code)
    rec = [t] + ([d or ""] if (d or h) else []) + ([h] if h and h != d else [])
    files[fname][code] = rec
for code, v in raw["device_error"].items():
    t = text(v)
    if not t: continue
    f = fam(code); fname = (f + "00") if f else code[:4]
    files[fname][code] = [t]
os.makedirs(outdir, exist_ok=True); tot = 0; big = 0
for k, v in files.items():
    s = json.dumps(v, separators=(",", ":"), ensure_ascii=False, sort_keys=True)
    open(f"{outdir}/{k}.json", "w").write(s); tot += len(s.encode()); big = max(big, len(s.encode()))
print("files", len(files), "total", tot, "largest", big, "dropped", dropped)
