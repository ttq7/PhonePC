"""Fake GitHub releases endpoint for testing the in-app updater.

Serves a releases/latest-shaped JSON plus the APK, so the whole update path
(request -> parse -> download -> signature check) can be exercised without
publishing a throwaway release.

Run:  python tools/fake_release.py [port]
"""
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
APK = r'E:\此电脑\dist\此手机-2.5-debug.apk'

if not os.path.isfile(APK):
    print('APK not found:', APK)
    sys.exit(1)
SIZE = os.path.getsize(APK)

PAYLOAD = {
    "tag_name": "9.9",
    "name": "v9.9 测试版本",
    "body": "这是用于验证更新流程的假版本。\n\n- 用于测试下载与签名校验\n- 不会被真正安装",
    "assets": [
        {"name": "PhonePC-9.9-debug.apk",
         "size": SIZE,
         "browser_download_url": "http://127.0.0.1:%d/app.apk" % PORT}
    ],
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("  fake-release %s\n" % (fmt % args))

    def do_GET(self):
        if self.path.startswith('/latest'):
            body = json.dumps(PAYLOAD).encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path.startswith('/app.apk'):
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Length', str(SIZE))
            self.end_headers()
            with open(APK, 'rb') as f:
                while True:
                    chunk = f.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
        else:
            self.send_response(404)
            self.end_headers()


if __name__ == '__main__':
    print('fake release server on 127.0.0.1:%d  (apk %.2f MB)' % (PORT, SIZE / 1e6))
    HTTPServer(('127.0.0.1', PORT), Handler).serve_forever()
