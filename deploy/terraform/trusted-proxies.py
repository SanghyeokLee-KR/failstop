#!/usr/bin/env python3
"""ALB와 공식 CloudFront origin 주소만 Tomcat trusted-proxy 정규식으로 변환한다."""
import ipaddress
import json
import re
import urllib.request

URL = "https://ip-ranges.amazonaws.com/ip-ranges.json"
OCTET = r"(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"


def network_pattern(cidr):
    network = ipaddress.IPv4Network(cidr, strict=True)
    if network.prefixlen < 8:
        raise ValueError("An excessively broad proxy range is not allowed")
    first = network.network_address.packed
    last = network.broadcast_address.packed
    components = []
    for low, high in zip(first, last):
        components.append(str(low) if low == high else OCTET if low == 0 and high == 255
                          else "(?:" + "|".join(str(value) for value in range(low, high + 1)) + ")")
    return r"\.".join(components)


def compile_ranges(alb_subnets, document):
    cloudfront = {entry["ip_prefix"] for entry in document["prefixes"]
                  if entry.get("service") == "CLOUDFRONT_ORIGIN_FACING"}
    if not cloudfront or len(cloudfront) > 1024:
        raise ValueError("Expected a bounded, nonempty CloudFront origin-facing address list")
    result = "^(?:" + "|".join(network_pattern(cidr) for cidr in sorted(set(alb_subnets) | cloudfront)) + ")$"
    if len(result) > 65536:
        raise ValueError("Proxy expression is unexpectedly large")
    re.compile(result)
    return result


def fetch_ranges():
    # urllib validates the AWS HTTPS certificate. Redirects to another host are rejected.
    with urllib.request.urlopen(URL, timeout=15) as response:
        if response.url != URL:
            raise ValueError("Unexpected IP range document redirect")
        raw = response.read(4 * 1024 * 1024 + 1)
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError("IP range document exceeds the size limit")
    return json.loads(raw)
