"""Read named credentials or pasted provider keys without evaluating file contents."""
import re
from pathlib import Path
from urllib.parse import urlsplit


def read_secret(path, kind):
    source = Path(path)
    if source.stat().st_size > 16384:
        raise ValueError("secret_file_too_large")
    text = source.read_text(encoding="utf-8-sig")
    if kind == "teams":
        candidates = re.findall(r"https://[^\s\"'<>]+", text)
        candidates = [value for value in candidates
                      if (urlsplit(value).hostname or "").endswith((".logic.azure.com", ".api.powerplatform.com"))]
    elif kind == "gemini":
        candidates = re.findall(r"(?<![A-Za-z0-9_])(?:AIza[A-Za-z0-9_-]{35}|AQ\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*={0,2})(?![A-Za-z0-9_])", text)
        for line in text.splitlines():
            match = re.match(r"\s*(?:GEMINI_API_KEY|GOOGLE_API_KEY|REPORT_AI_KEY)\s*=\s*(.+?)\s*$", line)
            if match:
                candidates.append(match.group(1).strip("\"'"))
    else:
        raise ValueError("unknown_secret_kind")
    candidates = set(candidates)
    if len(candidates) != 1:
        raise ValueError("secret_missing_or_ambiguous")
    value = candidates.pop()
    if not 20 <= len(value) <= 4096 or any(character.isspace() for character in value):
        raise ValueError("invalid_secret_format")
    return value
