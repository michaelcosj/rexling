#!/usr/bin/env python3
"""Display a four-color Kitty graphics test in the current terminal."""
import base64
import sys

pixels = bytes([
    255, 0, 0, 255,   0, 255, 0, 255,
    0, 0, 255, 255,   255, 255, 0, 255,
])
payload = base64.b64encode(pixels).decode("ascii")
sys.stdout.write(
    "\033_Ga=T,f=32,s=2,v=2,c=20,r=10,q=2;" + payload + "\033\\"
)
sys.stdout.flush()
