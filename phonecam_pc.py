#!/usr/bin/env python3
"""
PhoneCam Pro PC — padanan IriunWebcam.exe (versi PC), fitur lengkap gratis.

Mempelajari arsitektur IriunWebcam-2.9.9.exe (hasil reverse engineering):
- engine.dll  : koneksi ke HP via mDNS (_iriunwebcam._tcp.) atau adb forward
                port 4697/4699, dekode H264 (avcodec-62.dll) → preview + virtual cam
- exe WPF    : UI kontrol (torch, zoom, ISO, WB, exposure, focus, mic)
- driver INF : "Iriun Webcam #2/#3/#4" = virtual camera via Media Foundation
                CustomCaptureSource (CLSID unik per slot kamera)

PhoneCam Pro PC memakai arsitektur yang setara tapi open:
- Auto-discovery HP: mDNS _phonecam._tcp. + scan subnet + adb forward
- Viewer MJPEG (preview window)
- KONTROL PENUH dari PC: torch, zoom, switch kamera, resolusi
- Output: OBS Virtual Camera / v4l2loopback (Linux) / window capture

Dependensi: pip install opencv-python zeroconf
Opsional Linux virtual cam: sudo apt install v4l2loopback-dkms
"""
import argparse
import base64
import json
import socket
import subprocess
import sys
import threading
import time
import urllib.request

try:
    import cv2
    import numpy as np
except ImportError:
    print("Butuh: pip install opencv-python")
    sys.exit(1)

try:
    from zeroconf import ServiceBrowser, Zeroconf
    HAVE_ZC = True
except ImportError:
    HAVE_ZC = False


# ---------------------------------------------------------------------------
# Discovery — padanan engine.dll: mDNS + adb
# ---------------------------------------------------------------------------

class MdnsFinder:
    """Cari HP PhoneCam via mDNS _phonecam._tcp.local. (ala _iriunwebcam._tcp.)."""

    def __init__(self):
        self.found = []
        self._lock = threading.Lock()

    def _on_service(self, zc, type_, name):
        try:
            info = zc.get_service_info(type_, name, timeout=3000)
            if info and info.addresses:
                addr = socket.inet_ntoa(info.addresses[0])
                port = info.port or 8085
                with self._lock:
                    if (addr, port) not in self.found:
                        self.found.append((addr, port))
                        print(f"  [mDNS] HP ditemukan: {addr}:{port}")
        except Exception:
            pass

    def find(self, timeout=4):
        if not HAVE_ZC:
            return []
        zc = Zeroconf()
        browser = ServiceBrowser(zc, "_phonecam._tcp.local.", listener=type("L", (), {
            "add_service": lambda zc, t, n: self._on_service(zc, t, n),
            "update_service": lambda *a: None,
            "remove_service": lambda *a: None,
        })())
        time.sleep(timeout)
        zc.close()
        return list(self.found)


def adb_forward(local_port=4699, phone_port=8085):
    """Padanan persis engine.dll Iriun: `adb forward tcp:4699 tcp:8085`.
    Setiap device USB terpasang mendapat tunnel → PC konek via 127.0.0.1:4699.
    (Hasil RE: engine.dll memakai adb.exe + `forward tcp:` + port 4697/4699)"""
    out = []
    try:
        r = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=5)
        for line in r.stdout.splitlines()[1:]:
            parts = line.split("\t")
            if len(parts) >= 2 and parts[1].strip() == "device":
                dev = parts[0]
                subprocess.run(
                    ["adb", "-s", dev, "forward", f"tcp:{local_port}", f"tcp:{phone_port}"],
                    capture_output=True, timeout=5)
                out.append(("127.0.0.1", local_port, dev))
                print(f"  [USB] adb forward {dev}: 127.0.0.1:{local_port} → hp:{phone_port}")
    except FileNotFoundError:
        pass
    except Exception:
        pass
    return out


def scan_subnet(prefix, port=8085, timeout=0.3, limit=254):
    """Scan cepat /24 — fallback tanpa mDNS."""
    found = []
    def probe(i):
        try:
            with socket.create_connection((f"{prefix}.{i}", port), timeout=timeout):
                found.append((f"{prefix}.{i}", port))
        except Exception:
            pass
    threads = []
    for i in range(1, limit + 1):
        t = threading.Thread(target=probe, args=(i,), daemon=True)
        t.start()
        threads.append(t)
        if len(threads) >= 64:
            for t in threads: t.join()
            threads = []
    for t in threads: t.join()
    return found


# ---------------------------------------------------------------------------
# Stream MJPEG + kontrol — padanan engine.dll decode & kontrol remote
# ---------------------------------------------------------------------------

class PhoneCamClient:
    def __init__(self, host, port=8085, passcode=""):
        self.base = f"http://{host}:{port}"
        self.auth = base64.b64encode(f":{passcode}".encode()).decode() if passcode else None

    def _req(self, path, timeout=5):
        req = urllib.request.Request(self.base + path)
        if self.auth:
            req.add_header("Authorization", "Basic " + self.auth)
        return urllib.request.urlopen(req, timeout=timeout)

    def status(self):
        with self._req("/status") as r:
            return json.loads(r.read().decode())

    # --- Kontrol Pro (fitur berbayar di Iriun, gratis di sini) ---
    def torch(self, on):    self._req(f"/control?cmd=torch&v={'on' if on else 'off'}")
    def camera(self, side): self._req(f"/control?cmd=camera&v={side}")
    def zoom(self, z):      self._req(f"/control?cmd=zoom&v={z}")
    def quality(self, q):   self._req(f"/control?cmd=quality&v={q}")

    def frames(self):
        """Generator JPEG frames dari /mjpeg."""
        req = urllib.request.Request(self.base + "/mjpeg")
        if self.auth:
            req.add_header("Authorization", "Basic " + self.auth)
        boundary = b"--frame"
        buf = b""
        with urllib.request.urlopen(req, timeout=10) as r:
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                buf += chunk
                while True:
                    start = buf.find(boundary)
                    if start < 0:
                        break
                    hdr_end = buf.find(b"\r\n\r\n", start)
                    if hdr_end < 0:
                        break
                    length = 0
                    for line in buf[start:hdr_end].split(b"\r\n"):
                        if b"Content-Length:" in line:
                            length = int(line.split(b":")[1].strip())
                    end = hdr_end + 4 + length
                    if len(buf) < end:
                        break
                    yield buf[hdr_end + 4:end]
                    buf = buf[end + 2:]


# ---------------------------------------------------------------------------
# Virtual camera output — padanan driver "Iriun Webcam #2/#3/#4"
# Windows: via OBS Virtual Camera (direkomendasikan) atau pyvirtualcam
# Linux  : via v4l2loopback (/dev/video*)
# ---------------------------------------------------------------------------

class VirtualCam:
    def __init__(self, w, h, fps=30, device="/dev/video10"):
        self.w, self.h, self.fps = w, h, fps
        self.device = device
        self.backend = None
        try:  # Linux
            import v4l2capture  # type: ignore
            self.backend = "v4l2"
        except Exception:
            pass
        try:  # pyvirtualcam (Windows/Linux, OBS host)
            import pyvirtualcam  # type: ignore
            self.pvc = pyvirtualcam.Camera(width=w, height=h, fps=fps, device=device if device else None)
            self.backend = "pyvirtualcam"
            print(f"[virtual-cam] pyvirtualcam aktif ({w}x{h}@{fps})")
        except Exception:
            self.backend = None
            print("[virtual-cam] tidak aktif — pakai OBS: tambah window capture "
                  "window 'PhoneCam Pro PC', lalu Start Virtual Camera")

    def send(self, bgr):
        if self.backend == "pyvirtualcam":
            self.pvc.send(bgr)
            self.pvc.sleep_until_next_frame()


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="PhoneCam Pro PC — viewer + kontrol penuh")
    ap.add_argument("target", nargs="?", help="IP:port HP (mis. 192.168.1.10:8085); kosong = auto-discovery")
    ap.add_argument("--passcode", default="", help="passcode bila diaktifkan di HP")
    ap.add_argument("--no-window", action="store_true", help="tanpa preview window")
    ap.add_argument("--virtual-cam", default="", help="device virtual cam (Linux: /dev/video10)")
    ap.add_argument("--quality", type=int, default=1, choices=[0, 1, 2], help="0=720p 1=1080p 2=4K")
    args = ap.parse_args()

    target = args.target
    if not target:
        print("🔍 Mencari HP PhoneCam Pro...")
        cands = []
        if HAVE_ZC:
            cands += MdnsFinder().find(3)
        if not cands:
            # scan subnet WiFi lokal
            ip = socket.gethostbyname(socket.gethostname())
            if ip and not ip.startswith("127."):
                prefix = ip.rsplit(".", 1)[0]
                print(f"   scan subnet {prefix}.0/24 ...")
                cands += scan_subnet(prefix)
        if not cands:
            cands += adb_forward()  # USB — padanan Iriun adb forward tcp:4699
        if not cands:
            print("❌ HP tidak ditemukan. Pastikan app PhoneCam Pro aktif di HP, "
                  "atau jalankan: python phonecam_pc.py 192.168.x.x:8085")
            sys.exit(1)
        host, port = cands[0][0], cands[0][1]
        target = f"{host}:{port}"
        print(f"✅ Terhubung ke {target}")

    host, _, port = target.rpartition(":")
    port = int(port) if port else 8085
    cli = PhoneCamClient(host, port, args.passcode)

    try:
        st = cli.status()
        print(f"📱 {st.get('app','PhoneCam')} — {st.get('resolution','?')} @ {st.get('fps',0)}fps")
    except Exception as e:
        print(f"⚠ tidak bisa baca status: {e}")

    cli.quality(args.quality)

    zoom = 1
    vcam = None
    fps_t, fps_n, fps_show = time.time(), 0, 0.0

    for frame in cli.frames():
        arr = np.frombuffer(frame, np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            continue

        if vcam is None:
            vcam = VirtualCam(img.shape[1], img.shape[0], 30, args.virtual_cam or None)
        if vcam.backend == "pyvirtualcam":
            vcam.send(img)

        fps_n += 1
        now = time.time()
        if now - fps_t >= 1:
            fps_show = fps_n / (now - fps_t)
            fps_t, fps_n = now, 0

        if not args.no_window:
            hud = img.copy()
            cv2.putText(hud, f"{fps_show:.1f} FPS  zoom={zoom}x",
                        (12, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 120), 2)
            cv2.putText(hud, "[f]cam [t]torch [1-5]zoom [q]quit",
                        (12, hud.shape[0] - 14), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 200, 255), 2)
            cv2.imshow("PhoneCam Pro PC", hud)
            k = cv2.waitKey(1) & 0xFF
            if k == ord("q"):
                break
            elif k == ord("f"):
                cli.camera("front" if not st.get("front_camera") else "back")
            elif k == ord("t"):
                cli.torch(True)
            elif ord("1") <= k <= ord("5"):
                zoom = k - ord("0")
                cli.zoom(zoom)

    if not args.no_window:
        cv2.destroyAllWindows()
    print("selesai.")


if __name__ == "__main__":
    main()
