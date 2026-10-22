# PhoneCam Pro PC

Padanan **IriunWebcam.exe versi PC** — hasil reverse engineering IriunWebcam-2.9.9:

| Komponen Iriun (RE) | Padanan PhoneCam Pro PC |
|---|---|
| `engine.dll` — koneksi mDNS `_iriunwebcam._tcp.` + adb forward port 4697/4699 | `phonecam_pc.py` — mDNS `_phonecam._tcp.` + scan subnet + adb |
| `avcodec-62.dll` (H264 decode) | OpenCV decode MJPEG |
| Driver INF "Iriun Webcam #2/#3/#4" (Media Foundation CustomCaptureSource) | OBS Virtual Camera / v4l2loopback / pyvirtualcam |
| UI WPF: torch, zoom, ISO, WB, exp, focus, mic | HUD keyboard: `[f]` cam, `[t]` torch, `[1-5]` zoom |
| ESetPasscode / Pro unlock | **semua fitur aktif gratis** |

## Pakai
```bash
pip install opencv-python zeroconf
python phonecam_pc.py                      # auto-discovery
python phonecam_pc.py 192.168.1.10:8085    # manual
python phonecam_pc.py --passcode 1234     # bila pakai passcode
python phonecam_pc.py --virtual-cam /dev/video10   # Linux virtual cam
```

Tanpa virtual-cam device: window preview muncul → OBS: Window Capture → Start Virtual Camera.

## Test
```bash
python test/fake_phone.py 18085    # terminal 1: simulasi HP
python test/test_pc_client.py      # terminal 2: end-to-end → ✅ PASS
```
