import concurrent.futures, urllib.request
from pathlib import Path
SOURCES=[
 "https://raw.githubusercontent.com/saeidrahmanbd/BDIX-IPTV/main/IPTV-Playlist.m3u",
 "https://iptv-org.github.io/iptv/index.m3u",
 "https://raw.githubusercontent.com/AdeelWajid/iptv_validated_list/main/playlist.m3u"]
def fetch(u):
 req=urllib.request.Request(u,headers={"User-Agent":"BDWorldLiveTV/1.0"})
 with urllib.request.urlopen(req,timeout=25) as r:return r.read().decode("utf-8","replace")
def parse(t):
 meta=""; rows=[]
 for raw in t.splitlines():
  s=raw.strip()
  if s.startswith("#EXTINF"):meta=s
  elif s.startswith(("http://","https://")) and meta:rows.append((meta,s));meta=""
 return rows
def probe(row):
 meta,url=row
 try:
  req=urllib.request.Request(url,headers={"User-Agent":"VLC/3.0.20","Range":"bytes=0-1023"})
  with urllib.request.urlopen(req,timeout=7) as r:
   if r.status in (200,206):return row
 except Exception:pass
 return None
def main():
 all_rows={}
 for src in SOURCES:
  try:
   for row in parse(fetch(src)):all_rows.setdefault(row[1],row)
  except Exception as e:print("Source skipped:",src,e)
 with concurrent.futures.ThreadPoolExecutor(max_workers=35) as pool:
  good=[x for x in pool.map(probe,all_rows.values()) if x]
 out=Path("generated/combined.m3u");out.parent.mkdir(exist_ok=True)
 with out.open("w",encoding="utf-8") as f:
  f.write("#EXTM3U\n")
  for meta,url in good:f.write(meta+"\n"+url+"\n")
 print("Unique URLs:",len(all_rows),"HTTP reachable:",len(good))
 print("HTTP reachability is not proof of playback or 720p resolution.")
if __name__=="__main__":main()
