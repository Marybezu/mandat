#!/usr/bin/env python3
"""Opens a Mandat backup on a computer, without the app (for emergencies and for audits).

    pip install "cryptography>=44"
    python3 decrypt_backup.py archive-2026-01-01.bin out.json

The password is asked for and never shown. The result is the backup's data as plain JSON,
readable only by you (file mode 600). Keep it somewhere safe and delete it when done.

File layout: salt (16) | nonce (12) | AES-256-GCM ciphertext with tag; salt and nonce are
the associated data. Key: Argon2id, 64 MiB, 3 passes, 1 lane, 32 bytes, password in UTF-8 NFC.
"""
import getpass
import os
import sys
import unicodedata

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

PROFILES = [(64 * 1024, 3, 1)]  # (memory KiB, passes, lanes), newest first; same as the app


def decrypt(data: bytes, password: str, profiles=PROFILES) -> bytes:
    if len(data) < 16 + 12 + 16 or len(data) > 64 * 1024 * 1024:
        raise SystemExit("Not a Mandat backup")
    salt, nonce, header = data[:16], data[16:28], data[:28]
    secret = unicodedata.normalize("NFC", password).encode("utf-8")
    for memory_kib, passes, lanes in profiles:
        key = Argon2id(salt=salt, length=32, iterations=passes, lanes=lanes,
                       memory_cost=memory_kib).derive(secret)
        try:
            return AESGCM(key).decrypt(nonce, data[28:], header)
        except Exception:
            continue
    raise SystemExit("Wrong password, or the file is damaged")


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    with open(sys.argv[1], "rb") as f:
        data = f.read()
    plain = decrypt(data, getpass.getpass("Password: "))
    fd = os.open(sys.argv[2], os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(plain)
    print("Done:", sys.argv[2])


if __name__ == "__main__":
    main()
