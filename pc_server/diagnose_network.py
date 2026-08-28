"""
WireShare PC Server - Network & Firewall Diagnostic Tool
Helps debug 10000ms connection timeout errors by checking local IPs, testing port binding, and hosting a browser-accessible test page.
"""

import socket
import sys
from http.server import HTTPServer, BaseHTTPRequestHandler

class DiagnosticHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-type", "text/html; charset=utf-8")
        self.end_headers()
        client_ip = self.client_address[0]
        html = f"""
        <html>
        <head><title>WireShare Network Diagnostic</title></head>
        <body style="font-family: Arial, sans-serif; background: #0f172a; color: #f8fafc; padding: 40px; text-align: center;">
            <h1 style="color: #4ade80;">🟢 WireShare Network Test Successful!</h1>
            <h2>Your Android phone ({client_ip}) can successfully communicate with your PC!</h2>
            <p style="color: #94a3b8; font-size: 18px;">
                Windows Firewall and router rules are configured correctly.<br>
                You can now close this test script and start <b>python main.py</b>.
            </p>
        </body>
        </html>
        """
        self.wfile.write(html.encode("utf-8"))

    def log_message(self, format, *args):
        print(f"[Diagnostic] Received HTTP test request from {self.client_address[0]}")

def get_all_ips():
    ips = []
    try:
        hostname = socket.gethostname()
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                ips.append(ip)
    except Exception:
        pass
    return ips

def run_diagnostic():
    print("=" * 65)
    print("      WireShare Network & Firewall Diagnostic Tool")
    print("=" * 65)
    
    ips = get_all_ips()
    print("\n1. Detected Local IPv4 Addresses on this PC:")
    for ip in ips:
        print(f"   -> {ip}")
    if not ips:
        print("   [WARNING] No local network IP found. Check Wi-Fi connection!")
    
    port = 8765
    print(f"\n2. Testing Port Binding on 0.0.0.0:{port}...")
    try:
        server = HTTPServer(("0.0.0.0", port), DiagnosticHandler)
        print(f"   [OK] Successfully bound to port {port}.")
    except OSError as e:
        print(f"   [ERROR] Could not bind to port {port}: {e}")
        print("           Is 'python main.py' or another app already running on port 8765?")
        sys.exit(1)

    print("\n" + "=" * 65)
    print("               HOW TO TEST WITH YOUR PHONE")
    print("=" * 65)
    print("1. Open Chrome or Safari on your Android phone.")
    print("2. Type one of the following URLs into your phone's browser:")
    for ip in ips:
        print(f"   👉  http://{ip}:{port}")
    print("\n3. If you see '🟢 WireShare Network Test Successful!' in your phone browser:")
    print("   -> Your Wi-Fi and Firewall are working! Press Ctrl+C and run 'python main.py'.")
    print("\n4. If your phone browser says 'ERR_CONNECTION_TIMED_OUT':")
    print("   -> Your PC's Firewall or Antivirus (McAfee/Norton/Bitdefender) is blocking port 8765,")
    print("      OR your router has Wi-Fi AP Isolation enabled.")
    print("=" * 65)
    print("\n[Diagnostic] Waiting for connection from phone... (Press Ctrl+C to stop)")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[Diagnostic] Stopped.")

if __name__ == "__main__":
    run_diagnostic()
