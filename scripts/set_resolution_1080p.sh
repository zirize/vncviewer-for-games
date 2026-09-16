#!/bin/bash
# Forces an Android device to 1080p.

echo "=== pinning the device to 1080p (1920x1080) ==="
echo "This changes the default ADB device's screen resolution to 1080x1920."

adb devices | grep -v "List" | grep "device$"
if [ $? -ne 0 ]; then
    echo "No device found. Check that one is connected over USB or Wi-Fi."
    exit 1
fi

echo "Setting the resolution to 1080x1920..."
adb shell wm size 1080x1920

echo "Setting the density to 420, the usual value for 1080p..."
adb shell wm density 420

echo "Done - check the device screen."
echo "Run reset_resolution.sh to put it back."
