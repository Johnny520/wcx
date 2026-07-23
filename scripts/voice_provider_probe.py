#!/usr/bin/env python3
"""PC-side behavior probe for WeKit's fixed public voice providers.

References:
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/CJq.java
  /home/ujhhgtg/coding/funbox_deobf_main/funbox_payload/ELl.java
  /home/ujhhgtg/coding/funbox_deobf/output/decrypted_strings.json

The probe deliberately runs independently of Android. It verifies that RingDuoDuo's
encrypted category/search payloads still produce playable object URLs and that Uoice
filters pending-review records before probing an audition response.
"""

from __future__ import annotations

import argparse
import base64
import time
import urllib.parse
import xml.etree.ElementTree as ET

import requests
from Crypto.Cipher import DES
from Crypto.Util.Padding import pad

RING_ENDPOINT = "http://ring.shoujiduoduo.com/ring_enc.php"
RING_OBJECT_HOST = "http://cdnringbd.shoujiduoduo.com"
RING_SUFFIX = "&os=ar&ver=8.9.36.0&startid=YKkVXsjl1wiS8lwqPrvFcqFHuVzshHz6"
RING_CATEGORY_PREFIX = (
    "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk"
    "&dev=UAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=getlist&listid="
)
RING_SEARCH_PREFIX = (
    "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk"
    "&dev=HUAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=search&keyword="
)


def ring_url(plain: str) -> str:
    encrypted = DES.new(b"hikmpuF9", DES.MODE_ECB).encrypt(pad(plain.encode(), 8))
    encoded = base64.encodebytes(encrypted).decode()
    return f"{RING_ENDPOINT}?q={urllib.parse.quote_plus(encoded)}{RING_SUFFIX}"


def ring_items(session: requests.Session, plain: str) -> list[dict[str, str]]:
    response = session.get(ring_url(plain), timeout=30)
    response.raise_for_status()
    root = ET.fromstring(response.text)
    base_url = root.attrib.get("baseurl", RING_OBJECT_HOST).rstrip("/")
    result: list[dict[str, str]] = []
    for element in root.findall("ring"):
        raw_url = element.attrib.get("mp3url", "")
        if raw_url:
            item = dict(element.attrib)
            item["url"] = (
                raw_url
                if raw_url.startswith("http")
                else f"{base_url}/{raw_url.lstrip('/')}"
            )
            result.append(item)
    return result


def assert_audio(response: requests.Response, label: str) -> None:
    response.raise_for_status()
    prefix = response.content[:16]
    content_type = response.headers.get("content-type", "")
    if response.content.lstrip().startswith(b"{") or not content_type.startswith(
        "audio/"
    ):
        raise RuntimeError(
            f"{label} did not return audio: type={content_type!r}, bytes={len(response.content)}, head={prefix!r}",
        )
    print(
        f"{label}: type={content_type}, bytes={len(response.content)}, head={prefix.hex()}"
    )


def probe_ring(session: requests.Session) -> None:
    timestamp = int(time.time() * 1000)
    category_plain = (
        f"{RING_CATEGORY_PREFIX}20&from=&page=1&pagesize=25&uid="
        f"&ptime=2023-08-24&tstamp={timestamp}"
    )
    category = ring_items(session, category_plain)
    if not category:
        raise RuntimeError("RingDuoDuo category returned no playable entries")
    print(f"RingDuoDuo category: {len(category)} playable entries")
    assert_audio(
        session.get(category[0]["url"], timeout=30), "RingDuoDuo category audio"
    )

    search_plain = (
        f"{RING_SEARCH_PREFIX}爱&src=input&page=1&pagesize=15&include=all&ctdb=1&cudb=1"
        f"&ptime=2023-08-24&tstamp={timestamp}"
    )
    search = ring_items(session, search_plain)
    print(f"RingDuoDuo search: {len(search)} playable entries")


def probe_uoice(session: requests.Session) -> None:
    catalog = session.get(
        "https://uoice.com/v1/voice/list?page=1&take=50&count=0&sort=2", timeout=30
    )
    catalog.raise_for_status()
    packs = catalog.json()["data"]["result"]
    pending = 0
    for pack in packs:
        detail = session.get(
            f"https://uoice.com/v1/voice?id={pack['id']}&keyword=null&fromType=0",
            timeout=30,
        )
        detail.raise_for_status()
        for item in detail.json()["data"].get("item", []):
            if int(item.get("state", 0)) != 0:
                pending += 1
                continue
            audio = session.get(
                f"https://uoice.com/v1/voice/audition?id={item['id']}", timeout=30
            )
            assert_audio(audio, "Uoice audition")
            print(f"Uoice filtered pending entries before sample: {pending}")
            return
    raise RuntimeError("Uoice catalog contained no approved audition entries")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--skip-audio", action="store_true", help="Only validate provider listings"
    )
    args = parser.parse_args()
    with requests.Session() as session:
        session.headers["User-Agent"] = "okhttp/4.12.0"
        if args.skip_audio:
            category = ring_items(
                session,
                f"{RING_CATEGORY_PREFIX}20&from=&page=1&pagesize=25&uid=&ptime=2023-08-24&tstamp={int(time.time() * 1000)}",
            )
            print(f"RingDuoDuo category: {len(category)} playable entries")
            return
        probe_ring(session)
        probe_uoice(session)


if __name__ == "__main__":
    main()
