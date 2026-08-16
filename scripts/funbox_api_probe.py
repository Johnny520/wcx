#!/usr/bin/env python3
"""PC-side probe for the FunBox private binary API.

This intentionally mirrors the recovered Java transport instead of importing WeKit code,
so it can expose behavioral differences in the Kotlin implementation. It only sends fields
required by operations 100 (server probe) and 10 (sticker catalog).

References:
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/AiJ.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/BW8.java (Ae9)
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/F70.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/Byc.java (AaE)
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/GHd.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/F4O.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/F5i.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/F5n.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/EPJ.java
"""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import secrets
import string
import struct
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Callable, Iterable, TypeVar

import requests
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7

RESOLVER_NAME = "resolve.fpfast.top"
RESOLVER_URL = "https://dns.google/resolve"
ALPHABET = string.ascii_lowercase + string.ascii_uppercase + string.digits
MAX_FIELD_BYTES = 64 * 1024 * 1024
MAX_LIST_ITEMS = 100_000

CURVE_P = int("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16)
CURVE_A = int("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16)
CURVE_N = int("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16)
GENERATOR = (
    int("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16),
    int("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16),
)
SERVER_PUBLIC = (
    int("D185A433AA9DA51E898E8C32F4CB8EB4EB9F8A063E41588670F1789C79CD0142", 16),
    int("1228586967283B151A12D72A039B92C31D527FE0BC4CAA897C114A6AB0B47209", 16),
)


class ProtocolError(RuntimeError):
    pass


class ServerStatusError(ProtocolError):
    def __init__(self, status: int, encrypted_size: int) -> None:
        super().__init__(
            f"server envelope status={status}, encrypted_size={encrypted_size}"
        )
        self.status = status
        self.encrypted_size = encrypted_size


def random_text(length: int) -> str:
    return "".join(secrets.choice(ALPHABET) for _ in range(length))


class BinaryWriter:
    """Recovered GHd writer. The key uses Java's Math.random() byte range 0..254."""

    def __init__(self) -> None:
        self._key = bytes(secrets.randbelow(255) for _ in range(32))
        self._key_index = 0
        self._output = bytearray()

    def _xor_key(self, value: bytes) -> bytes:
        result = bytearray(len(value))
        for index, byte in enumerate(value):
            result[index] = byte ^ self._key[self._key_index]
            self._key_index = (self._key_index + 1) % len(self._key)
        return bytes(result)

    def int(self, value: int) -> None:
        self._output.extend(self._xor_key(struct.pack("<I", value & 0xFFFFFFFF)))

    def long(self, value: int) -> None:
        self._output.extend(
            self._xor_key(struct.pack("<Q", value & 0xFFFFFFFFFFFFFFFF))
        )

    def bytes(self, value: bytes | None) -> None:
        data = value or b""
        self.int(len(data))
        transformed = bytes(
            byte ^ ((index + 0x80) % 0xFF) for index, byte in enumerate(data)
        )
        self._output.extend(self._xor_key(transformed))

    def string(self, value: str | None) -> None:
        self.bytes((value or "").encode("utf-8"))

    def build(self) -> bytes:
        encoded_key = bytes(
            byte ^ ((index + 0x80) % 0xFF) for index, byte in enumerate(self._key)
        )
        return encoded_key + bytes(self._output)


T = TypeVar("T")


class BinaryReader:
    def __init__(self, data: bytes) -> None:
        if len(data) < 32:
            raise ProtocolError(f"GHd object is too short: {len(data)} bytes")
        self._data = memoryview(data)
        self._offset = 32
        self._key = bytes(data[index] ^ ((index + 0x80) % 0xFF) for index in range(32))
        self._key_index = 0

    @property
    def remaining(self) -> int:
        return len(self._data) - self._offset

    def _read_raw(self, length: int) -> bytes:
        end = self._offset + length
        if length < 0 or end > len(self._data):
            raise ProtocolError(
                f"truncated GHd object: need {length} bytes at {self._offset}, total={len(self._data)}"
            )
        value = self._data[self._offset : end].tobytes()
        self._offset = end
        return value

    def _read_xor(self, length: int) -> bytes:
        raw = self._read_raw(length)
        result = bytearray(length)
        for index, byte in enumerate(raw):
            result[index] = byte ^ self._key[self._key_index]
            self._key_index = (self._key_index + 1) % len(self._key)
        return bytes(result)

    def int(self) -> int:
        return struct.unpack("<i", self._read_xor(4))[0]

    def long(self) -> int:
        return struct.unpack("<q", self._read_xor(8))[0]

    def bytes(self) -> bytes:
        length = self.int()
        if not 0 <= length <= MAX_FIELD_BYTES:
            raise ProtocolError(f"invalid GHd field length: {length}")
        data = self._read_xor(length)
        if not any(data):
            return bytes(length)
        return bytes(byte ^ ((index + 0x80) % 0xFF) for index, byte in enumerate(data))

    def string(self) -> str:
        return self.bytes().decode("utf-8")

    def objects(self, decode: Callable[["BinaryReader"], T]) -> list[T]:
        count = self.int()
        if not 0 <= count <= MAX_LIST_ITEMS:
            raise ProtocolError(f"invalid GHd list size: {count}")
        return [decode(BinaryReader(self.bytes())) for _ in range(count)]


def int32(value: int) -> int:
    value &= 0xFFFFFFFF
    return value if value < 0x80000000 else value - 0x100000000


def tea_crypt(key_text: str, data: bytes, encrypt: bool) -> bytes:
    key = key_text.encode()[:16].ljust(16, b"\0")
    padded = data if len(data) % 8 == 0 else data + bytes(8 - len(data) % 8)
    keys = struct.unpack(">4i", key)
    output = bytearray(padded)
    for offset in range(0, len(output), 8):
        v0, v1 = struct.unpack_from(">2i", output, offset)
        total = 0 if encrypt else int32(0xC6EF3720)
        for _ in range(32):
            if encrypt:
                total = int32(total - 0x61C88647)
                mix0 = (
                    int32(int32(v1 << 4) + keys[0])
                    ^ int32(v1 + total)
                    ^ int32((v1 >> 5) + keys[1])
                )
                v0 = int32(v0 + mix0)
                mix1 = (
                    int32(int32(v0 << 4) + keys[2])
                    ^ int32(v0 + total)
                    ^ int32((v0 >> 5) + keys[3])
                )
                v1 = int32(v1 + mix1)
            else:
                mix1 = (
                    int32(int32(v0 << 4) + keys[2])
                    ^ int32(v0 + total)
                    ^ int32((v0 >> 5) + keys[3])
                )
                v1 = int32(v1 - mix1)
                mix0 = (
                    int32(int32(v1 << 4) + keys[0])
                    ^ int32(v1 + total)
                    ^ int32((v1 >> 5) + keys[1])
                )
                v0 = int32(v0 - mix0)
                total = int32(total + 0x61C88647)
        struct.pack_into(">2i", output, offset, v0, v1)
    return bytes(output)


def sm3(data: bytes) -> bytes:
    digest = hashes.Hash(hashes.SM3())
    digest.update(data)
    return digest.finalize()


def point_add(first: tuple[int, int], second: tuple[int, int]) -> tuple[int, int]:
    x1, y1 = first
    x2, y2 = second
    if x1 == x2:
        if y1 != y2:
            raise ProtocolError("SM2 point at infinity")
        numerator = (3 * x1 * x1 + CURVE_A) % CURVE_P
        denominator = pow((2 * y1) % CURVE_P, -1, CURVE_P)
    else:
        numerator = (y2 - y1) % CURVE_P
        denominator = pow((x2 - x1) % CURVE_P, -1, CURVE_P)
    slope = numerator * denominator % CURVE_P
    x3 = (slope * slope - x1 - x2) % CURVE_P
    y3 = (slope * (x1 - x3) - y1) % CURVE_P
    return x3, y3


def point_multiply(point: tuple[int, int], scalar: int) -> tuple[int, int]:
    result: tuple[int, int] | None = None
    addend = point
    while scalar > 0:
        if scalar & 1:
            result = addend if result is None else point_add(result, addend)
        addend = point_add(addend, addend)
        scalar >>= 1
    if result is None:
        raise ProtocolError("invalid zero SM2 scalar")
    return result


def encode_point(point: tuple[int, int]) -> bytes:
    return b"\x04" + point[0].to_bytes(32, "big") + point[1].to_bytes(32, "big")


def java_bigint_bytes(value: int) -> bytes:
    """Positive java.math.BigInteger#toByteArray, including its sign-preserving 00 byte."""
    raw = value.to_bytes(max(1, (value.bit_length() + 7) // 8), "big")
    return b"\0" + raw if raw[0] & 0x80 else raw


def sm2_kdf(seed: bytes, length: int) -> bytes:
    output = bytearray()
    counter = 1
    while len(output) < length:
        output.extend(sm3(seed + counter.to_bytes(4, "big")))
        counter += 1
    return bytes(output[:length])


def sm2_encrypt(data: bytes) -> bytes:
    if not data:
        raise ValueError("SM2 input must not be empty")
    while True:
        scalar = secrets.randbelow(CURVE_N - 1) + 1
        c1 = point_multiply(GENERATOR, scalar)
        shared = point_multiply(SERVER_PUBLIC, scalar)
        mask = sm2_kdf(encode_point(shared), len(data))
        if any(mask):
            break
    c2 = bytes(left ^ right for left, right in zip(data, mask))
    c3 = sm3(java_bigint_bytes(shared[0]) + data + java_bigint_bytes(shared[1]))
    return encode_point(c1) + c2 + c3


def join_txt_fragments(value: str) -> str:
    value = value.strip()
    if not value.startswith('"'):
        return value
    decoder = json.JSONDecoder()
    fragments: list[str] = []
    offset = 0
    while offset < len(value):
        while offset < len(value) and value[offset].isspace():
            offset += 1
        if offset >= len(value):
            break
        fragment, consumed = decoder.raw_decode(value[offset:])
        if not isinstance(fragment, str):
            raise ProtocolError("TXT fragment is not a string")
        fragments.append(fragment)
        offset += consumed
    return "".join(fragments)


def resolve_candidates(session: requests.Session) -> tuple[list[str], list[str]]:
    response = session.get(
        RESOLVER_URL,
        params={"name": RESOLVER_NAME, "type": "TXT"},
        timeout=20,
    )
    response.raise_for_status()
    answers = response.json().get("Answer", [])
    records = [
        join_txt_fragments(item["data"]) for item in answers if item.get("type") == 16
    ]
    if not records:
        raise ProtocolError("resolver returned no TXT records")
    last_error: Exception | None = None
    for record in records:
        try:
            decoded = json.loads(base64.b64decode(record).decode("utf-8"))
            return list(decoded["vapi"]), list(decoded["vraw"])
        except (KeyError, ValueError, json.JSONDecodeError) as error:
            last_error = error
    raise ProtocolError(f"resolver returned no valid address record: {last_error}")


def request_proof(now: float | None = None) -> str:
    current = time.time() if now is None else now
    local = datetime.fromtimestamp(current)
    day_of_year = local.timetuple().tm_yday
    return str(int(current) * local.hour + day_of_year)


@dataclass
class WireResult:
    host: str
    operation: int
    http_status: int
    content_type: str | None
    response_size: int
    envelope_status: int
    encrypted_size: int
    payload: bytes


def call(
    session: requests.Session,
    host: str,
    operation: int,
    payload: bytes,
    *,
    send_content_type: bool,
) -> WireResult:
    session_key = random_text(32)
    envelope = BinaryWriter()
    envelope.int(operation)
    envelope.bytes(tea_crypt(session_key, payload, encrypt=True))
    envelope.bytes(sm2_encrypt(session_key.encode()))
    envelope.long(int(time.time() * 1000))
    envelope.string(random_text(8))
    envelope.long(len(payload))
    body = gzip.compress(envelope.build())
    headers = {"ph": request_proof()}
    if send_content_type:
        headers["Content-Type"] = "application/octet-stream"
    response = session.post(
        host.rstrip("/") + "/funbox/api/req2",
        data=body,
        headers=headers,
        timeout=(30, 60),
    )
    response.raise_for_status()
    try:
        outer = BinaryReader(response.content)
        encrypted = outer.bytes()
        status = outer.int()
    except ProtocolError as error:
        prefix = response.content[:48]
        raise ProtocolError(
            f"cannot decode F5n: HTTP {response.status_code}, "
            f"content_type={response.headers.get('content-type')!r}, "
            f"size={len(response.content)}, prefix_hex={prefix.hex()}, prefix_text={prefix!r}: {error}"
        ) from error
    if not encrypted:
        raise ProtocolError(
            f"server returned an empty encrypted payload with status={status}"
        )
    result = WireResult(
        host=host,
        operation=operation,
        http_status=response.status_code,
        content_type=response.headers.get("content-type"),
        response_size=len(response.content),
        envelope_status=status,
        encrypted_size=len(encrypted),
        payload=b"",
    )
    if status != 0:
        raise ServerStatusError(status, len(encrypted))
    result.payload = tea_crypt(session_key, encrypted, encrypt=False)
    return result


def probe_payload() -> bytes:
    writer = BinaryWriter()
    writer.long(0)
    return writer.build()


def catalog_payload(account: str) -> bytes:
    writer = BinaryWriter()
    writer.string(account)
    return writer.build()


def pack_payload(pack_id: str, account: str) -> bytes:
    writer = BinaryWriter()
    writer.string(pack_id)
    writer.string(account)
    return writer.build()


def decode_probe(payload: bytes) -> dict[str, object]:
    reader = BinaryReader(payload)
    return {
        "status": reader.int(),
        "message": reader.string(),
        "data_size": len(reader.bytes()),
        "padding_size": reader.remaining,
    }


def decode_catalog_pack(reader: BinaryReader) -> dict[str, object]:
    return {
        "id": reader.string(),
        "title": reader.string(),
        "flags": reader.int(),
        "created_at": reader.long(),
        "category": reader.string(),
        "download_count": reader.int(),
        "click_count": reader.int(),
        "description": reader.string(),
        "thumb_id": reader.string(),
        "updated_at": reader.long(),
    }


def decode_catalog(payload: bytes) -> list[dict[str, object]]:
    return BinaryReader(payload).objects(decode_catalog_pack)


def decode_sticker(reader: BinaryReader) -> dict[str, str]:
    return {
        "md5": reader.string().upper(),
        "source": reader.string(),
        "image_id": reader.string(),
        "ocr": reader.string(),
        "thumb_id": reader.string(),
    }


def decode_pack(payload: bytes) -> dict[str, object]:
    reader = BinaryReader(payload)
    status = reader.int()
    items = reader.objects(decode_sticker)
    message = reader.string()
    if status != 0:
        raise ProtocolError(f"pack response status={status}, message={message!r}")
    return {"status": status, "message": message, "items": items}


def decode_object_response(response: requests.Response) -> tuple[bytes, bool]:
    security_key = response.headers.get("Sec")
    if not security_key:
        return response.content, False
    key = security_key.encode("utf-8")
    if len(key) != 16:
        raise ProtocolError(f"invalid object security key length: {len(key)}")
    decryptor = Cipher(algorithms.AES(key), modes.CBC(key)).decryptor()
    padded = decryptor.update(response.content) + decryptor.finalize()
    unpadder = PKCS7(128).unpadder()
    return unpadder.update(padded) + unpadder.finalize(), True


def image_type(data: bytes) -> str | None:
    if data.startswith(b"GIF"):
        return "gif"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    return None


def verify_object(
    session: requests.Session,
    object_host: str,
    object_type: str,
    object_id: str,
) -> bool:
    if not object_id:
        print(f"  {object_type}: skipped empty object id")
        return False
    url = object_host.rstrip("/") + f"/vfile/{object_type}/{object_id}"
    try:
        response = session.get(url, timeout=(30, 60))
        response.raise_for_status()
        decoded, secured = decode_object_response(response)
        media_type = image_type(decoded)
        print(
            f"  {object_type}: HTTP {response.status_code}, encrypted={secured}, "
            f"response_bytes={len(response.content)}, decoded_bytes={len(decoded)}, "
            f"type={media_type!r}, content_type={response.headers.get('content-type')!r}"
        )
        return bool(decoded) and media_type is not None
    except Exception as error:
        print(f"  {object_type}: {type(error).__name__}: {error}")
        return False


def print_call_result(label: str, result: WireResult, decoded: object) -> None:
    print(
        f"  {label}: HTTP {result.http_status}, envelope_status={result.envelope_status}, "
        f"response={result.response_size} B, encrypted={result.encrypted_size} B, "
        f"response_content_type={result.content_type!r}"
    )
    print("   decoded:", json.dumps(decoded, ensure_ascii=False, indent=2))


def run_variant(
    session: requests.Session,
    host: str,
    operation: int,
    payload: bytes,
    decoder: Callable[[bytes], object],
    send_content_type: bool,
) -> bool:
    label = "WeKit Content-Type" if send_content_type else "FunBox no Content-Type"
    try:
        result = call(
            session,
            host,
            operation,
            payload,
            send_content_type=send_content_type,
        )
        print_call_result(label, result, decoder(result.payload))
        return True
    except Exception as error:
        print(f"  {label}: {type(error).__name__}: {error}")
        return False


def unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--account",
        action="append",
        default=[],
        help="WeChat login username/wxid for operation 10; may be repeated (default: empty string)",
    )
    parser.add_argument(
        "--host", action="append", default=[], help="API host override; may be repeated"
    )
    parser.add_argument(
        "--skip-catalog", action="store_true", help="only run operation 100"
    )
    parser.add_argument(
        "--check-objects",
        action="store_true",
        help="load one pack with operation 2 and download its cover/thumb/image objects",
    )
    parser.add_argument(
        "--http-mode",
        choices=("both", "funbox", "wekit"),
        default="both",
        help="compare absent vs application/octet-stream Content-Type",
    )
    args = parser.parse_args()

    session = requests.Session()
    session.headers["User-Agent"] = "okhttp/4.12.0"
    api_hosts, object_hosts = resolve_candidates(session)
    hosts = unique(args.host or api_hosts)
    accounts = unique(args.account) if args.account else [""]
    modes = {
        "both": (False, True),
        "funbox": (False,),
        "wekit": (True,),
    }[args.http_mode]

    print("Resolver API hosts:", api_hosts)
    print("Resolver object hosts:", object_hosts)
    for object_host in object_hosts:
        try:
            response = session.get(
                object_host.rstrip("/") + "/vfile/vfun/vtest", timeout=20
            )
            print(
                f"Object probe {object_host}: HTTP {response.status_code}, body={response.text!r}"
            )
        except Exception as error:
            print(f"Object probe {object_host}: {type(error).__name__}: {error}")

    any_success = False
    for host in hosts:
        print(f"\nAPI host: {host}")
        print(" operation 100:")
        for mode in modes:
            any_success |= run_variant(
                session, host, 100, probe_payload(), decode_probe, mode
            )
        if args.skip_catalog:
            continue
        for account in accounts:
            display_account = "<empty>" if not account else f"<{len(account)} chars>"
            print(f" operation 10, account={display_account}:")
            for mode in modes:
                any_success |= run_variant(
                    session,
                    host,
                    10,
                    catalog_payload(account),
                    lambda payload: {
                        "pack_count": len(packs := decode_catalog(payload)),
                        "first_packs": packs[:3],
                    },
                    mode,
                )

    if args.check_objects and not args.skip_catalog and hosts and object_hosts:
        account = accounts[0]
        print("\nCatalog -> pack -> object end-to-end check:")
        try:
            catalog_result = call(
                session,
                hosts[0],
                10,
                catalog_payload(account),
                send_content_type=False,
            )
            packs = decode_catalog(catalog_result.payload)
            pack = packs[0]
            print(
                f" selected pack={pack['title']!r}, clicks={pack['click_count']}, "
                f"downloads={pack['download_count']}"
            )
            pack_result = call(
                session,
                hosts[0],
                2,
                pack_payload(str(pack["id"]), account),
                send_content_type=False,
            )
            decoded_pack = decode_pack(pack_result.payload)
            items = decoded_pack["items"]
            print(
                f" operation 2: envelope_status={pack_result.envelope_status}, "
                f"decoded_items={len(items)}, message={decoded_pack['message']!r}"
            )
            first_item = items[0]
            checks = [
                verify_object(session, object_hosts[0], "thumb", str(pack["thumb_id"])),
                verify_object(
                    session, object_hosts[0], "thumb", first_item["thumb_id"]
                ),
                verify_object(
                    session, object_hosts[0], "image", first_item["image_id"]
                ),
            ]
            any_success |= all(checks)
        except Exception as error:
            print(f" end-to-end check: {type(error).__name__}: {error}")
    return 0 if any_success else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
