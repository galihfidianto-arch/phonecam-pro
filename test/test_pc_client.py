"""Test end-to-end client PhoneCam PC vs simulasi HP."""
import sys, time

sys.argv = ['phonecam_pc.py', '127.0.0.1:18085', '--no-window']
src = open('/home/gsh-server/phonecam/phonecam_pc.py').read()

# Jalankan main() tapi hentikan setelah beberapa frame
import types
ns = {'__name__': 'phonecam_pc_test'}
exec(src, ns)

cli = ns['PhoneCamClient']('127.0.0.1', 18085, '')
st = cli.status()
print('status:', st)
assert st['app'] == 'PhoneCam Pro', 'status gagal'

# Kontrol
print('torch:', cli.torch(True) or 'ok')
print('zoom:', cli.zoom(2) or 'ok')
print('camera:', cli.camera('back') or 'ok')
print('quality:', cli.quality(2) or 'ok')

# Frames — ambil 10 frame
n = 0
t0 = time.time()
import cv2, numpy as np
for frame in cli.frames():
    img = cv2.imdecode(np.frombuffer(frame, np.uint8), cv2.IMREAD_COLOR)
    assert img is not None and img.shape[2] == 3, 'frame rusak'
    n += 1
    if n >= 10:
        break
dt = time.time() - t0
print(f'frames diterima: {n} dalam {dt:.2f}s ({n/dt:.1f} fps)')
assert n == 10, 'frame kurang'
print('✅ END-TO-END TEST PASS')
