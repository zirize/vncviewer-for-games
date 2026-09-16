#!/usr/bin/env python3
"""Do modifiers stay pressed on a VNC server after the client dies?

🔑 Why it matters: after an abnormal exit (force-stop, network drop) the client **cannot send** the
   releases - the socket is already gone. So if they persist, there is no way to fix it from inside
   the app, and sending an up per modifier right after connecting becomes necessary insurance. If
   they do not persist, that insurance is dead weight.

Measured 2026-09-15: **on Xtigervnc (Xvnc) they do not persist.** The server releases the keys a
   departing client had held.
❓ **x11vnc has never been measured.** It is not installed on this host, and planting a stuck CTRL
   on somebody else's live desktop is not something to do: being unable to fix it in place is the
   whole point of the experiment.
   🔑 Once x11vnc can be started in isolation, point this script at it unchanged.

Usage:
    vncserver :8 -rfbport 5908 -localhost yes -SecurityTypes None -geometry 800x600 -depth 24
    python3 modifier_persist_test.py            # defaults to :8 / 5908
    vncserver -kill :8

🔴 **No control, no verdict.** If the key is not detected as pressed *before* disconnecting, this
   script dies rather than reporting. "Nothing found" in that state does not mean "nothing
   persists", it means "nothing was measured". The instrument was wrong twice while developing
   this, and both times it looked like a plausible "no keys pressed" - a false negative.
"""
import os, socket, struct, subprocess, sys, time

HOST = os.environ.get('VNCBENCH_HOST', '127.0.0.1')
PORT = int(os.environ.get('VNCBENCH_PORT', '5908'))
DISP = os.environ.get('VNCBENCH_DISPLAY', ':8')
CTRL_L = 0xFFE3


# 🔴 Querying the master keyboard (3) **always comes back empty** - verified by genuinely holding
#    a key with xdotool. Presses show up on the slave devices: XTEST=5, TigerVNC=7.
#    Measuring device 3 first produced a false negative in which even the control was empty.
# 🔑 master (3) does not support xinput --query-state at all ("unable to find device").
DEVICES = {'5': 'XTEST', '7': 'TigerVNC'}


def keys_down():
    hits = []
    for dev, name in DEVICES.items():
        # 🔴 Assembling env by hand once dropped XAUTHORITY, and xinput failed silently: empty
        #    stdout, the error only on stderr ⇒ it read as "no keys pressed". A false negative.
        r = subprocess.run(['xinput', '--query-state', dev],
                           env={**os.environ, 'DISPLAY': DISP},
                           capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f"🔴 measurement failed - xinput({dev}): {r.stderr.strip()}")
        for l in r.stdout.splitlines():
            if l.strip().endswith('=down'):
                hits.append(f"{name}:{l.strip()}")
    return hits


def connect():
    s = socket.create_connection((HOST, PORT), 5); s.settimeout(5)
    s.recv(12); s.sendall(b'RFB 003.008\n')
    n = s.recv(1)[0]; s.recv(n)
    s.sendall(bytes([1]))                       # security type None
    assert struct.unpack('>I', s.recv(4))[0] == 0, 'security handshake failed'
    s.sendall(bytes([1]))                       # shared
    init = s.recv(24)
    nl = struct.unpack('>I', init[20:24])[0]
    s.recv(nl)
    return s


def key(s, keysym, down):
    s.sendall(struct.pack('>BBHI', 4, 1 if down else 0, 0, keysym))


def hard_close(s):
    """Drops the connection with RST - the same shape as the app dying or the network going."""
    s.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack('ii', 1, 0))
    s.close()


def show(label):
    d = keys_down()
    print(f"  {label:<38} keys down: {d if d else 'none'}")
    return d


print("1. with nothing connected")
show("baseline")

print("\n2. client A connects -> ControlL down -> dropped with RST")
a = connect(); key(a, CTRL_L, True); time.sleep(0.4)
before = show("before the drop")
if not before:
    sys.exit("🔴 control failed - the key is not detected as down BEFORE the drop, so either the\n"
             "   instrument or the injection is wrong. This result means 'not measured', not\n"
             "   'does not persist'. No verdict.")
hard_close(a); time.sleep(1.2)
after = show("after the drop")

print("\n3. client B reconnects (sending nothing)")
b = connect(); time.sleep(0.6)
recon = show("just after reconnecting")

print("\n4. testing the remedy - one ControlL up right after connecting")
key(b, CTRL_L, False); time.sleep(0.6)
fixed = show("after normalising")
b.close()

print("\n-- verdict --")
print(f"  persists after a drop      : {'🔴 yes' if after else '✅ no'}")
print(f"  persists after reconnect   : {'🔴 yes' if recon else '✅ no'}")
print(f"  cleared by a single up     : {'✅ yes' if not fixed else '🔴 no'}")
