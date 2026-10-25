#!/usr/bin/env python3
"""Opens a Mandat backup on a computer, without the app (for emergencies and for audits).

    pip install cryptography
    python3 decrypt_backup.py mandat-backup.mandat out.json

The password is asked for and never shown. The result is the backup's data as plain JSON,
so keep it somewhere safe and delete it when you no longer need it.
"""
import getpass
import struct
import sys
import unicodedata

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

MAGIC = b"MANDATBK"


def decrypt(data: bytes, password: str) -> bytes:
    if data[:8] != MAGIC or data[8] != 1 or data[9] != 1:
        raise SystemExit("Not a Mandat backup (or made by a newer version)")
    memory_kib, iterations = struct.unpack(">ii", data[10:18])
    parallelism = data[18]
    pos = 19
    salt_len = data[pos]; pos += 1
    salt = data[pos:pos + salt_len]; pos += salt_len
    nonce_len = data[pos]; pos += 1
    nonce = data[pos:pos + nonce_len]; pos += nonce_len
    header = data[:pos]
    key = Argon2id(salt=salt, length=32, iterations=iterations, lanes=parallelism,
                   memory_cost=memory_kib).derive(
        unicodedata.normalize("NFC", password).encode("utf-8"))
    return AESGCM(key).decrypt(nonce, data[pos:], header)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    with open(sys.argv[1], "rb") as f:
        data = f.read()
    plain = decrypt(data, getpass.getpass("Password: "))
    with open(sys.argv[2], "wb") as f:
        f.write(plain)
    print("Done:", sys.argv[2])


if __name__ == "__main__":
    main()
