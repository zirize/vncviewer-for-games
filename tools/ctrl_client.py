import socket, struct, time, sys
# 🔑 The target is never written into the source - somebody's address would stay in the
#    repository. Usage: VNC_HOST=<host> [VNC_PORT=5900] python3 ctrl_client.py
import os
HOST = os.environ.get('VNC_HOST') or sys.exit('set VNC_HOST')
PORT = int(os.environ.get('VNC_PORT', '5900'))

def rx(s, n):
    b = b''
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c: raise Exception("closed")
        b += c
    return b

s = socket.create_connection((HOST, PORT), timeout=10)
print("ProtocolVersion:", rx(s, 12).decode().strip())
s.sendall(b'RFB 003.008\n')
n = rx(s, 1)[0]; types = list(rx(s, n))
print("security types:", types)
if 1 not in types:
    print("no None security offered - stopping"); sys.exit(1)
s.sendall(bytes([1]))
print("SecurityResult:", struct.unpack('>I', rx(s, 4))[0])
s.sendall(b'\x01')                                    # ClientInit: shared
w, h = struct.unpack('>HH', rx(s, 4))
pf = rx(s, 16)
name = rx(s, struct.unpack('>I', rx(s, 4))[0]).decode('latin1')
print(f"ServerInit: {w}x{h}  name={name!r}")
print(f"  server pixel format: bpp={pf[0]} depth={pf[1]} big={pf[2]} true={pf[3]}")

# SetEncodings: Raw (0) only
s.sendall(b'\x02\x00' + struct.pack('>H', 1) + struct.pack('>i', 0))

def request(inc, x, y, ww, hh):
    s.sendall(struct.pack('>BBHHHH', 3, inc, x, y, ww, hh))

def read_update(label):
    t0 = time.time()
    s.settimeout(6)
    try:
        t = rx(s, 1)[0]
    except socket.timeout:
        print(f"  [{label}] ⛔ no reply for 6s (timeout)"); return False
    if t != 0:
        print(f"  [{label}] unexpected message type {t}"); return False
    rx(s, 1)
    nr = struct.unpack('>H', rx(s, 2))[0]
    total = 0
    for _ in range(nr):
        hdr = rx(s, 12)
        rx_, ry, rw, rh, enc = struct.unpack('>HHHHi', hdr)
        if enc == 0:
            total += len(rx(s, rw * rh * (pf[0] // 8)))
    print(f"  [{label}] ✅ replied in {time.time()-t0:.3f}s  rects={nr}  pixels={total}B")
    return True

print("\n-- repeating NON-incremental requests the same way (a 64x64 area) --")
ok = 0
for i in range(1, 6):
    request(0, 0, 0, 64, 64)
    if read_update(f"non-incremental #{i}"): ok += 1
    time.sleep(1)

print(f"\nresult: {ok} of 5 non-incremental requests were answered")
s.close()
