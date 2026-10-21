# PhoneCam Pro 📱🎥

**HP Android jadi webcam 4K untuk PC — semua fitur Pro aktif, tanpa cloud.**

Kloning open-source Iriun Webcam dengan fitur Pro gratis:
- ✅ **Tanpa watermark** (fitur berbayar di Iriun)
- ✅ **Passcode** proteksi koneksi (fitur berbayar di Iriun)
- ✅ Switch kamera depan/belakang
- ✅ Torch/flash
- ✅ Zoom digital 1–5x
- ✅ Pilih resolusi: 720p / 1080p / 4K
- ✅ Auto-discovery via mDNS `_phonecam._tcp.`
- ✅ Video langsung HP→PC (WiFi/USB) — **tidak lewat server siapa pun**

## Cara Pakai

### 1. Build APK
```bash
# di Android Studio, atau:
./gradlew assembleRelease
# hasil: app/build/outputs/apk/release/app-release.apk
```

### 2. Di HP
1. Install APK → buka app
2. (Opsional) isi passcode
3. Tekan **Mulai Server Kamera**
4. Catat IP yang tampil (mis. `http://192.168.1.10:8085`)

### 3. Di PC
**Cara 1 — Browser:** buka `http://<ip-hp>:8085` → viewer + tombol kontrol muncul

**Cara 2 — OBS / Zoom / Teams:**
- OBS: Source → **Browser** → URL `http://<ip-hp>:8085/` (atau Media Source dengan `/mjpeg`)
- Zoom/Teams: pakai viewer Python di bawah → muncul sebagai window, share via window capture, atau pakai [OBS Virtual Camera]

### 4. Kontrol dari PC (HTTP API)
```text
GET /control?cmd=torch&v=on      # torch nyala
GET /control?cmd=camera&v=front  # kamera depan
GET /control?cmd=zoom&v=2        # zoom 2x
GET /control?cmd=quality&v=2     # 4K (2=4K, 1=1080p, 0=720p)
GET /snapshot.jpg                # ambil 1 frame
GET /status                      # JSON status
```
Dengan passcode aktif, semua endpoint pakai HTTP Basic Auth (user kosong, password = passcode).

## Arsitektur
```text
┌───────────── HP Android ─────────────┐
│ Kotlin + Camera2 (YUV→JPEG)           │
│ ImageReader 30fps → MJPEG encoder     │
│ CamHttpServer (port 8085)             │
│   /mjpeg  → multipart stream          │
│   /control → kontrol kamera remote    │
└──────────────┬────────────────────────┘
               │ WiFi / USB tethering (HTTP)
┌──────────────▼─────────── PC ┌───────┐
│ Browser / OBS / Python viewer │  OBS  │
└───────────────────────────────┴───────┘
```

## File
```text
app/src/main/java/com/gsh/phonecam/
├── MainActivity.kt         UI utama + status
├── CameraServerService.kt  Foreground service
├── CameraEngine.kt         Camera2 + YUV→JPEG + kontrol
├── CamHttpServer.kt        HTTP/MJPEG server + viewer web
├── Prefs.kt                Penyimpanan setting
└── NetUtils.kt             Deteksi IP
phonecam_viewer.py          Viewer Python di PC (preview window)
```

## Perbedaan vs Iriun
| | Iriun Free | Iriun Pro | **PhoneCam Pro** |
|---|---|---|---|
| Watermark | ada | hilang | **tidak ada** |
| Passcode | ❌ | ✅ | ✅ |
| Kontrol remote | terbatas | ✅ | ✅ |
| Cloud | tidak | tidak | **tidak** |
| Iklan | ada | tidak | **tidak** |
| Open source | ❌ | ❌ | ✅ MIT |
