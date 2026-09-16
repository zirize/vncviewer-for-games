#!/bin/bash
# Restores an Android device to its default resolution.

echo "=== resetting device resolution ==="

adb devices | grep -v "List" | grep "device$"
if [ $? -ne 0 ]; then
    echo "No device found. Check that one is connected over USB or Wi-Fi."
    exit 1
fi

echo "Restoring the default resolution..."
adb shell wm size reset

echo "Restoring the default density..."
adb shell wm density reset

echo "Done."
