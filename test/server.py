"""Fake Plex Media Server for testing CarPlex (Windows) end to end."""
import hashlib
import io
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
MOVIE = os.path.join(HERE, "media", "movie.mkv")
EPISODE = os.path.join(HERE, "media", "episode.mkv")
LOG = []
STATE = {"offline": False, "rate": 4_000_000, "latency": 0.0}
IMG_CACHE = {}

GENRES = ["Action", "Drama", "Western", "Thriller", "Comedy"]


def media(path, dur):
    size = os.path.getsize(path)
    return [{"container": "mkv", "videoCodec": "h264", "audioCodec": "aac", "audioChannels": 2,
             "videoResolution": "720", "bitrate": int(size * 8 / (dur / 1000) / 1000),
             "Part": [{"key": None, "size": size, "container": "mkv", "duration": dur}]}]


ITEMS = {}


def add(d):
    ITEMS[str(d["ratingKey"])] = d
    return d


for i in range(30):
    rk = 100 + i
    m = media(MOVIE, 180000)
    m[0]["Part"][0]["key"] = f"/library/parts/{rk}/1/file.mkv"
    m[0]["Part"][0]["Stream"] = [{"streamType": 1, "codec": "h264"},
                                 {"streamType": 3, "key": "/library/streams/900", "languageCode": "fre",
                                  "displayTitle": "French (SRT External)"}]
    add({"ratingKey": str(rk), "key": f"/library/metadata/{rk}", "type": "movie",
         "title": ["Iron Horizon", "The Long Road", "Dust and Thunder", "Harvest Moon", "Midnight Freight"][i % 5]
         + (f" {i // 5 + 1}" if i >= 5 else ""),
         "year": 2000 + i % 25, "contentRating": ["PG", "PG-13", "R"][i % 3], "audienceRating": 6.0 + (i % 40) / 10,
         "tagline": "Some roads only go one way." if i == 1 else "",
         "summary": "A long-haul driver crosses the high plains with a load that isn't what it seems. "
                    "Storms, broken radios and a dispatcher who won't stop calling make for one long night." ,
         "duration": 180000, "librarySectionID": 1 if i < 25 else 3, "thumb": f"/thumb/m{rk}", "art": f"/art/m{rk}",
         "addedAt": 1700000000 + i * 1000, "viewCount": 1 if i in (3, 4) else 0,
         "viewOffset": 60000 if i == 1 else 0,
         "Genre": [{"tag": GENRES[i % 5]}, {"tag": GENRES[(i + 2) % 5]}],
         "Director": [{"tag": "Jane Director"}],
         "Role": [{"tag": f"Actor {n}", "role": f"Character {n}"} for n in range(1, 9)],
         "Media": m})

add({"ratingKey": "200", "type": "show", "title": "Prairie Patrol", "year": 2021, "contentRating": "TV-14",
     "audienceRating": 8.4, "studio": "High Plains TV", "summary": "Marshals keep the peace across three counties.",
     "librarySectionID": 2, "thumb": "/thumb/s200", "art": "/art/s200", "leafCount": 7, "viewedLeafCount": 2,
     "childCount": 2, "Genre": [{"tag": "Drama"}, {"tag": "Western"}], "Role": [{"tag": "Lead Actor", "role": "Marshal"}]})
for si, (srk, n) in enumerate([("210", 4), ("220", 3)], start=1):
    add({"ratingKey": srk, "type": "season", "title": f"Season {si}", "index": si, "parentRatingKey": "200",
         "parentTitle": "Prairie Patrol", "librarySectionID": 2, "thumb": f"/thumb/se{srk}", "parentThumb": "/thumb/s200",
         "art": "/art/s200", "leafCount": n, "viewedLeafCount": 2 if si == 1 else 0})
    for e in range(1, n + 1):
        rk = str(int(srk) + e)
        m = media(EPISODE, 120000)
        m[0]["Part"][0]["key"] = f"/library/parts/{rk}/1/file.mkv"
        add({"ratingKey": rk, "type": "episode", "title": f"Episode Title {si}-{e}", "index": e, "parentIndex": si,
             "parentRatingKey": srk, "grandparentRatingKey": "200", "grandparentTitle": "Prairie Patrol",
             "grandparentThumb": "/thumb/s200", "grandparentArt": "/art/s200", "parentThumb": f"/thumb/se{srk}",
             "thumb": f"/still/e{rk}", "art": "/art/s200", "summary": f"Things get complicated in episode {e}.",
             "duration": 120000, "librarySectionID": 2, "contentRating": "TV-14", "year": 2021,
             "viewCount": 1 if rk in ("211", "213") else 0,
             "viewOffset": {"212": 30000, "213": 50000}.get(rk, 0), "Media": m})

SECTIONS = [{"key": "1", "title": "Movies", "type": "movie"}, {"key": "2", "title": "TV Shows", "type": "show"},
            {"key": "3", "title": "Kids Movies", "type": "movie"}, {"key": "9", "title": "Music", "type": "artist"}]


def mc(items, **extra):
    return {"MediaContainer": dict({"size": len(items), "Metadata": items}, **extra)}


def by_section(sec):
    return [d for d in ITEMS.values() if str(d.get("librarySectionID")) == sec and d["type"] in ("movie", "show")]


def eps_of(show):
    out = []
    for s in sorted([d for d in ITEMS.values() if d.get("parentRatingKey") == show and d["type"] == "season"],
                    key=lambda d: d["index"]):
        out += sorted([d for d in ITEMS.values() if d.get("parentRatingKey") == s["ratingKey"]], key=lambda d: d["index"])
    return out


def image(path, w, h):
    key = (path, w, h)
    if key in IMG_CACHE:
        return IMG_CACHE[key]
    hsh = hashlib.md5(path.encode()).digest()
    col = (60 + hsh[0] % 150, 40 + hsh[1] % 120, 40 + hsh[2] % 150)
    im = Image.new("RGB", (w, h), col)
    d = ImageDraw.Draw(im)
    for y in range(h):
        f = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c * (1 - 0.6 * f)) for c in col))
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", max(12, w // 9))
    except Exception:
        font = ImageFont.load_default()
    d.text((w * 0.08, h * 0.08), path.split("/")[-1].upper(), fill=(255, 255, 255), font=font)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=85)
    IMG_CACHE[key] = buf.getvalue()
    return IMG_CACHE[key]


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head=False):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        p = u.path
        if p.startswith("/_"):
            return self.control(p, q)
        if STATE["latency"]:
            time.sleep(STATE["latency"])
        if "/library/parts/" not in p and not p.startswith("/photo"):
            if q.get("X-Plex-Token") != "TESTTOKEN" and self.headers.get("X-Plex-Token") != "TESTTOKEN":
                return self.send_json({"error": "unauthorized"}, 401)
        if p == "/identity":
            return self.send_json({"MediaContainer": {"machineIdentifier": "mock"}})
        if p == "/library/sections":
            return self.send_json({"MediaContainer": {"Directory": SECTIONS}})
        if p == "/hubs/home/continueWatching":
            items = [ITEMS[k] for k in ("101", "212", "213") if ITEMS[k]["viewOffset"]]
            items += [d for d in ITEMS.values() if d["type"] in ("movie", "episode") and d["viewOffset"]
                      and d["ratingKey"] not in ("101", "212", "213")]
            return self.send_json({"MediaContainer": {"Hub": [{"hubIdentifier": "home.continue", "title": "Continue",
                                                               "Metadata": items}]}})
        m = re.match(r"/library/sections/(\d+)/(recentlyAdded|all)$", p)
        if m:
            items = by_section(m.group(1))
            if m.group(2) == "recentlyAdded":
                items = sorted(items, key=lambda d: -d.get("addedAt", 0))
            sort = q.get("sort", "titleSort")
            if sort.startswith("title"):
                items = sorted(items, key=lambda d: d["title"])
            elif sort.startswith("addedAt"):
                items = sorted(items, key=lambda d: -d.get("addedAt", 0))
            if q.get("unwatched") == "1":
                items = [d for d in items if not d.get("viewCount")]
            total = len(items)
            start = int(q.get("X-Plex-Container-Start", 0))
            size = int(q.get("X-Plex-Container-Size", 1000))
            return self.send_json(mc(items[start:start + size], totalSize=total))
        m = re.match(r"/library/metadata/(\d+)(/children|/allLeaves)?$", p)
        if m:
            rk, sub = m.group(1), m.group(2)
            if sub == "/children":
                kids = sorted([d for d in ITEMS.values() if d.get("parentRatingKey") == rk],
                              key=lambda d: d.get("index", 0))
                return self.send_json(mc(kids))
            if sub == "/allLeaves":
                return self.send_json(mc(eps_of(rk)))
            if rk not in ITEMS:
                return self.send_json(mc([]))
            it = dict(ITEMS[rk])
            if q.get("includeMarkers") == "1":
                if it["type"] == "episode":
                    it["Marker"] = [{"type": "intro", "startTimeOffset": 5000, "endTimeOffset": 20000},
                                    {"type": "credits", "startTimeOffset": 100000, "endTimeOffset": 120000,
                                     "final": True}]
                elif it["type"] == "movie":
                    it["Marker"] = [{"type": "intro", "startTimeOffset": 10000, "endTimeOffset": 30000}]
            return self.send_json(mc([it]))
        if p == "/hubs/search":
            t = q.get("query", "").lower()
            hubs = []
            for typ in ("movie", "show", "episode"):
                found = [d for d in ITEMS.values() if d["type"] == typ and t in d["title"].lower()]
                if found:
                    hubs.append({"type": typ, "Metadata": found[:10]})
            return self.send_json({"MediaContainer": {"Hub": hubs}})
        if p in ("/:/timeline", "/:/scrobble", "/:/unscrobble"):
            LOG.append({"path": p, **q, "t": time.time()})
            if p == "/:/timeline":
                it = ITEMS.get(q.get("ratingKey"))
                if it:
                    it["viewOffset"] = int(q.get("time", 0))
            elif p == "/:/scrobble":
                it = ITEMS.get(q.get("key"))
                if it:
                    it["viewCount"] = 1
                    it["viewOffset"] = 0
            else:
                it = ITEMS.get(q.get("key"))
                if it:
                    it["viewCount"] = 0
            return self.send_json({})
        if p == "/photo/:/transcode":
            data = image(q.get("url", "x"), int(q.get("width", 300)), int(q.get("height", 450)))
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "max-age=3600")
            self.end_headers()
            self.wfile.write(data)
            return
        if p == "/library/streams/900":
            body = b"1\n00:00:00,500 --> 00:03:00,000\nBonjour depuis le fichier externe\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        m = re.match(r"/library/parts/(\d+)/1/file.mkv", p)
        if m:
            return self.serve_file(EPISODE if ITEMS[m.group(1)]["type"] == "episode" else MOVIE, head)
        self.send_json({"error": "not found " + p}, 404)

    def control(self, p, q):
        if p == "/_offline":
            STATE["offline"] = q.get("on") == "1"
        if p == "/_offset":
            ITEMS[q["key"]]["viewOffset"] = int(q["ms"])
        if p == "/_latency":
            STATE["latency"] = float(q.get("s"))
        if p == "/_rate":
            STATE["rate"] = int(q.get("bps"))
        out = {"state": dict(STATE), "log": list(LOG)}
        if p == "/_reset":
            LOG.clear()
        self.send_json(out)

    def serve_file(self, path, head):
        if STATE["offline"]:
            self.connection.shutdown(socket.SHUT_RDWR)
            return
        size = os.path.getsize(path)
        rng = self.headers.get("Range")
        start, end = 0, size - 1
        if rng:
            mm = re.match(r"bytes=(\d*)-(\d*)", rng)
            if mm.group(1):
                start = int(mm.group(1))
            if mm.group(2):
                end = int(mm.group(2))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", "video/x-matroska")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        if head:
            return
        LOG.append({"path": "file", "range": rng, "t": time.time()})
        with open(path, "rb") as f:
            f.seek(start)
            left = end - start + 1
            chunk = 64 * 1024
            while left > 0:
                if STATE["offline"]:
                    try:
                        self.connection.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                    return
                data = f.read(min(chunk, left))
                if not data:
                    break
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    return
                left -= len(data)
                time.sleep(len(data) / STATE["rate"])


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 32400
    ThreadingHTTPServer.daemon_threads = True
    srv = ThreadingHTTPServer(("127.0.0.1", port), H)
    print("mock plex on", port, flush=True)
    srv.serve_forever()
