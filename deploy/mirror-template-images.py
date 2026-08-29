#!/usr/bin/env python3

"""Mirror current Memify template images into Yandex Object Storage."""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import pathlib
import re
import subprocess
import sys
import unicodedata
import urllib.parse
import urllib.request

import boto3
from botocore.config import Config


BUCKET_NAME = "memify-images"
PUBLIC_BUCKET_URL = f"https://storage.yandexcloud.net/{BUCKET_NAME}"
OBJECT_PREFIX = "templates/imgflip"
SUPPORTED_EXTENSIONS = {"jpg", "jpeg", "png", "webp"}


def parse_env(path: pathlib.Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def object_key(template_id: str, source_url: str) -> str:
    imgflip_id = template_id.removeprefix("imgflip-")
    path = pathlib.PurePosixPath(urllib.parse.urlparse(source_url).path)
    extension = path.suffix.lstrip(".").lower()
    if extension not in SUPPORTED_EXTENSIONS:
        extension = "jpg"
    return f"{OBJECT_PREFIX}/{imgflip_id}.{extension}"


def load_templates(api_url: str) -> list[dict[str, object]]:
    request = urllib.request.Request(
        api_url,
        headers={"User-Agent": "Memify template mirror/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def canonical_slug(name: str) -> str:
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode("ascii")
    without_apostrophes = ascii_name.replace("'", "").replace("`", "")
    return re.sub(r"[^A-Za-z0-9]+", "-", without_apostrophes).strip("-")


def download_image(template: dict[str, object]) -> tuple[bytes, str]:
    source_url = str(template["url"])
    source_extension = pathlib.PurePosixPath(urllib.parse.urlparse(source_url).path).suffix.lstrip(".").lower()
    if source_extension not in SUPPORTED_EXTENSIONS:
        source_extension = "jpg"
    slug = canonical_slug(str(template["name"]))
    proxied_source = urllib.parse.quote(source_url.removeprefix("https://"), safe="")
    candidates = [
        f"https://imgflip.com/s/meme/{slug}.{source_extension}",
        f"https://images.weserv.nl/?url={proxied_source}",
        source_url,
    ]

    last_error = ""
    for url in candidates:
        result = subprocess.run(
            [
                "curl",
                "--fail",
                "--silent",
                "--show-error",
                "--location",
                "--max-time",
                "60",
                url,
            ],
            check=False,
            capture_output=True,
        )
        if result.returncode == 0 and result.stdout:
            content_type = mimetypes.guess_type(source_url)[0] or "application/octet-stream"
            return result.stdout, content_type
        last_error = result.stderr.decode("utf-8", errors="replace").strip()

    raise RuntimeError(f"Could not download {template['id']}: {last_error}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", type=pathlib.Path, default=pathlib.Path(".env"))
    parser.add_argument(
        "--api-url",
        default="https://api.memify.space/templates?limit=1000&sort=best",
    )
    args = parser.parse_args()

    env = {**parse_env(args.env_file), **os.environ}
    access_key = env.get("YC_ACCESS_KEY_ID")
    secret_key = env.get("YC_SECRET_KEY")
    if not access_key or not secret_key:
        print("YC_ACCESS_KEY_ID and YC_SECRET_KEY are required", file=sys.stderr)
        return 2

    s3 = boto3.client(
        "s3",
        endpoint_url="https://storage.yandexcloud.net",
        region_name="ru-central1",
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        config=Config(signature_version="s3v4"),
    )

    templates = load_templates(args.api_url)
    if not templates:
        print("Template API returned no templates", file=sys.stderr)
        return 3

    mirrored = 0
    for index, template in enumerate(templates, start=1):
        template_id = str(template["id"])
        source_url = str(template["url"])
        if not template_id.startswith("imgflip-"):
            raise RuntimeError(f"Unsupported template id: {template_id}")

        key = object_key(template_id, source_url)
        payload, content_type = download_image(template)
        s3.put_object(
            Bucket=BUCKET_NAME,
            Key=key,
            Body=payload,
            ContentType=content_type,
            CacheControl="public, max-age=31536000, immutable",
        )
        mirrored += 1
        print(f"[{index}/{len(templates)}] {PUBLIC_BUCKET_URL}/{key}")

    print(f"Mirrored {mirrored} template images")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
