"""Server simulasi HP PhoneCam untuk test end-to-end di PC."""
import http.server, socketserver, io, time, sys
from PIL import Image

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18085

class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args): pass
    def do_GET(self):
        if self.path.startswith('/status'):
            body = b'{"app":"PhoneCam Pro","fps":29.8,"resolution":"1920x1080","passcode":false,"front_camera":true,"torch":false}'
            self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith('/control'):
            self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers()
            self.wfile.write(b'{"ok":true}')
        elif self.path.startswith('/mjpeg'):
            self.send_response(200)
            self.send_header('Content-Type','multipart/x-mixed-replace; boundary=frame')
            self.end_headers()
            for i in range(300):
                img = Image.new('RGB', (320, 240), (30 + i % 60, 100, 160))
                buf = io.BytesIO(); img.save(buf, 'JPEG')
                jpg = buf.getvalue()
                self.wfile.write(b'--frame\r\nContent-Type: image/jpeg\r\nContent-Length: '
                                 + str(len(jpg)).encode() + b'\r\n\r\n' + jpg + b'\r\n')
                time.sleep(0.033)
        else:
            self.send_response(404); self.end_headers()

with socketserver.TCPServer(("127.0.0.1", PORT), Handler) as s:
    print(f"simulasi HP di 127.0.0.1:{PORT}")
    s.serve_forever()
