import socket, threading, struct, sys, time
LISTEN = ('0.0.0.0', 5900)
# 🔑 The target is never written into the source - somebody's address would stay in the
#    repository. Usage: VNC_HOST=<host> [VNC_PORT=5900] python3 proxy2.py
import os
UPSTREAM = (os.environ.get('VNC_HOST') or sys.exit('set VNC_HOST'),
            int(os.environ.get('VNC_PORT', '5900')))
t0 = time.time()
def ts(): return f"{time.time()-t0:7.3f}"

CNAMES = {0:'SetPixelFormat',2:'SetEncodings',3:'FramebufferUpdateRequest',
          4:'KeyEvent',5:'PointerEvent',6:'ClientCutText',
          150:'EnableContinuousUpdates',248:'ClientFence',255:'QEMUClientMsg'}

def c2s(src, dst):
    """Client to server: logged in full as hex (it is small)."""
    handshake = 0
    buf = b''
    while True:
        try: data = src.recv(4096)
        except Exception as e: print(f"{ts()} C→S recv err {e}", flush=True); break
        if not data: print(f"{ts()} C→S closed", flush=True); break
        print(f"{ts()} C→S {len(data):5d}B  {data[:64].hex()}", flush=True)
        try: dst.sendall(data)
        except Exception as e: print(f"{ts()} C→S send err {e}", flush=True); break
    try: dst.shutdown(socket.SHUT_WR)
    except Exception: pass

def s2c(src, dst):
    """Server to client: summary only."""
    total = 0
    while True:
        try: data = src.recv(65536)
        except Exception as e: print(f"{ts()} S→C recv err {e}", flush=True); break
        if not data: print(f"{ts()} S→C closed", flush=True); break
        total += len(data)
        print(f"{ts()} S→C {len(data):6d}B (total {total})  {data[:24].hex()}", flush=True)
        try: dst.sendall(data)
        except Exception as e: print(f"{ts()} S→C send err {e}", flush=True); break

s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(LISTEN); s.listen(1)
print(f"{ts()} proxy listening {LISTEN} -> {UPSTREAM}", flush=True)
client, addr = s.accept()
print(f"{ts()} client {addr}", flush=True)
up = socket.create_connection(UPSTREAM)
print(f"{ts()} upstream connected", flush=True)
t1 = threading.Thread(target=c2s, args=(client, up), daemon=True)
t2 = threading.Thread(target=s2c, args=(up, client), daemon=True)
t1.start(); t2.start()
t1.join(timeout=40); 
print(f"{ts()} done", flush=True)
