#!/bin/bash
# Local LAN ping (latency) test.

if [ -z "$1" ]; then
    echo "usage: ./ping_test.sh <target-ip>"
    echo "example: ./ping_test.sh 192.0.2.10"
    exit 1
fi

TARGET_IP=$1

echo "=== pinging $TARGET_IP ==="
echo "For streaming a 2D game you want this to sit steadily between 1 and 5 ms."
echo "(Ctrl+C to stop)"
echo "------------------------------------------------"

ping -c 20 $TARGET_IP
