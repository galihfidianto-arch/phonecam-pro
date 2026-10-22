PhoneCam Pro PC — distribusi
=============================
Isi ZIP:
  PhoneCamProPC.exe   — aplikasi utama (PyInstaller onefile)
  adb/                — adb.exe resmi Google (platform-tools)
                        dipakai otomatis untuk USB streaming
                        (padanan adb.exe yang dibundel Iriun)

Cara pakai:
  1. Pastikan HP & PC di WiFi yang sama, atau colok USB
     (USB: aktifkan USB debugging di HP)
  2. Jalankan PhoneCamProPC.exe
  3. Auto-discovery: WiFi (mDNS/scan) → USB (adb forward tcp:4699)
  4. Kontrol: [f] kamera, [t] torch, [1-5] zoom, [q] keluar
  5. OBS/Zoom/Teams: Window Capture, atau OBS → Start Virtual Camera

Tanpa install apa pun — adb sudah dibundel.
