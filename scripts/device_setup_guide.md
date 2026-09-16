# Setting up an Android device for development

Only needed if you want to install and measure on real hardware. Building does not require it.

## 1. Developer mode and USB debugging

1. **Settings → About phone → Software information**
2. Tap **Build number** seven times to enable developer mode.
3. **Settings → Developer options**
4. Turn on **USB debugging**.

## 2. Debugging over Wi-Fi

A cable gets in the way when you are testing an on-screen gamepad with both hands, so wireless is
worth setting up.

### Android 11 and later

1. Put the device on **the same Wi-Fi network** as the computer.
2. **Settings → Developer options → Wireless debugging**, turn it on.
3. Pair, using the address and port the device shows:
   ```
   adb pair <ip>:<port>
   ```
4. Then connect. **The connection port is usually different from the pairing port** — read it off
   the device screen:
   ```
   adb connect <ip>:<connection-port>
   ```

### Android 10 and earlier (needs the cable once)

```bash
adb tcpip 5555          # with the cable connected
# unplug the cable
adb connect <device-ip>:5555
```

## 3. Resolution for testing

Pinning the device to 1080p keeps local streaming comparable between runs.

* set it: `./set_resolution_1080p.sh`
* put it back: `./reset_resolution.sh`

## 4. Latency

What matters for streaming is that ping is **steady**, not that it is low on average.

* `./ping_test.sh <computer-ip>`
