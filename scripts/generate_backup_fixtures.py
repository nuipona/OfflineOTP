#!/usr/bin/env python3
"""Regenerate synthetic compatibility fixtures, independently of app crypto.

Optional tooling only: Python 3 with cryptography==44.0.3. Never use these fixed
nonces or the test password for real accounts. Normal tests consume committed bytes.
"""
import hashlib
from pathlib import Path
import struct

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PASSWORD = b"test-only-password"
PLAINTEXT = '[{"label":"fixture-Å-☃","secret":"GEZDGNBVGY3TQOJQ"}]'.encode("utf-8")
SALT = bytes(range(12))
NONCE = bytes(range(32, 44))
ITERATIONS = 140000
DESTINATION = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/security"


def main():
    DESTINATION.mkdir(parents=True, exist_ok=True)
    current_key = hashlib.pbkdf2_hmac("sha1", PASSWORD, SALT, ITERATIONS, dklen=32)
    legacy_key = hashlib.sha256(PASSWORD).digest()
    fixtures = {
        "current.aes": struct.pack(">i", ITERATIONS) + SALT + NONCE + AESGCM(current_key).encrypt(NONCE, PLAINTEXT, None),
        "legacy.aes": NONCE + AESGCM(legacy_key).encrypt(NONCE, PLAINTEXT, None),
    }
    for name, data in fixtures.items():
        (DESTINATION / name).write_bytes(data)
        print(name, hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
