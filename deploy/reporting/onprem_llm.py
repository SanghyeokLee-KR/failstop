"""온프레미스 LLM(Ollama) 분석. gemini.py와 같은 지시문·근거 압축·출력 스키마를 쓰고 호출만 Ollama /api/chat이다.

근거 JSON은 운영 상태라 기관 밖으로 보내지 않는다. 그래서 모델 주소는 이 서버(루프백)나 사설망만 받는다.
루프백은 평문 http를 받고, 사설망의 평문 http는 ai_allow_insecure_http=true를 명시해야 받는다. 관리 JVM의
LlmClient와 같은 규칙이다(MSP 관제센터 2단계 설계 3절). 클라우드 모델 이름(:cloud, -cloud)은 로컬 Ollama가
프롬프트를 ollama.com으로 넘기므로 거부한다. 자동 재시도하지 않는다. 비용이 없어 Gemini의 호출 한도 장부를 쓰지 않는다.
"""
import copy
import ipaddress
import json
import re
import socket
import urllib.parse

from gemini import SYSTEM_INSTRUCTION, _schema, _shrink, compact_evidence
from transport import RequestFailure, json_request

DEFAULT_BASE_URL = "http://127.0.0.1:11434"
DEFAULT_MODEL = "qwen3.6:35b-a3b"
MODEL_NAME = re.compile(r"^[\w.:/-]{1,128}$")
THINK_VALUES = (False, True, "low", "medium", "high")
FIELDS = {"summary", "observations", "hypotheses", "next_checks"}
# 공통 지시문의 문장 예시(백업 경보 해제)를 로컬 모델이 다른 경보에도 그대로 베껴 첫 문장으로 썼다(2026-09-27, gpt-oss:20b,
# '앱 계층 CPU 높음 해제' 카드). Gemini 지시문은 그대로 두고 온프레미스 호출에만 덧붙인다.
ONPREM_NOTE = ("\n위 첫 문장 예시는 백업 경보일 때의 예다. 다른 경보에는 이 예시 문장을 쓰지 말고, trigger의 경보 이름·대상·"
               "전환(발생 또는 해제)으로 첫 문장을 새로 쓴다. 시각은 한국 시간(KST, UTC+9)으로 적는다.")


def _integer(config, name, default, minimum, maximum):
    value = config.get(name, default)
    if type(value) is not int or not minimum <= value <= maximum:
        raise RequestFailure("ollama_configuration_invalid")
    return value


def _addresses(host):
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        raise RequestFailure("ollama_host_unresolved") from None
    return {ipaddress.ip_address(info[4][0].split("%")[0]) for info in infos}


def chat_url(config):
    """설정의 기점 주소를 검사해 /api/chat 주소를 돌려준다. 루프백·사설 대역이 아니면 거부한다."""
    base = config.get("ai_base_url", DEFAULT_BASE_URL)
    parsed = urllib.parse.urlsplit(base if isinstance(base, str) else "")
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise RequestFailure("ollama_configuration_invalid")
    addresses = _addresses(parsed.hostname)
    if not addresses or not all(a.is_loopback or a.is_private for a in addresses):
        raise RequestFailure("ollama_remote_address")
    if (parsed.scheme == "http" and not all(a.is_loopback for a in addresses)
            and config.get("ai_allow_insecure_http") is not True):
        raise RequestFailure("ollama_plaintext_not_allowed")
    return f"{parsed.scheme}://{parsed.netloc}/api/chat"


def output_schema(fact_ids):
    """Gemini 형식에서 길이·개수 제한을 뺀 형식. llama.cpp의 형식 강제 문법은 maxLength 같은 반복 제한을 거대한
    규칙으로 펼쳐 생성을 수 분씩 늦춘다(2026-09-27 로컬 실측: 같은 근거로 2분 50초가 지나도 끝나지 않음).
    제한은 호출 뒤 공통 검증기(reporter.validate_analysis)가 그대로 확인한다. 근거 id는 실제 fact id로 묶는다."""
    schema = copy.deepcopy(_schema())

    def strip(node):
        if isinstance(node, dict):
            for key in ("minLength", "maxLength", "minItems", "maxItems"):
                node.pop(key, None)
            for value in node.values():
                strip(value)
        elif isinstance(node, list):
            for value in node:
                strip(value)
    strip(schema)
    if fact_ids:
        for name in ("observations", "hypotheses", "next_checks"):
            schema["properties"][name]["items"]["properties"]["evidence"]["items"] = {"type": "string", "enum": sorted(fact_ids)}
    return schema


def analyze(facts, config):
    """Return (four-field analysis, usage); caller applies the shared report validator."""
    model = config.get("ai_model", DEFAULT_MODEL)
    if (not isinstance(model, str) or not MODEL_NAME.match(model)
            or model.endswith(":cloud") or model.endswith("-cloud")):
        raise RequestFailure("ollama_model_not_allowed")
    think = config.get("ai_think", False)
    if think not in THINK_VALUES or type(think) not in (bool, str):
        raise RequestFailure("ollama_configuration_invalid")
    input_limit = _integer(config, "ai_max_input_tokens", 6000, 512, 12000)
    output_limit = _integer(config, "ai_max_output_tokens", 1200, 256, 2400)
    timeout = _integer(config, "ai_timeout", 30, 1, 45)
    url = chat_url(config)
    compact = compact_evidence(facts)

    def encode_evidence(value):
        return json.dumps({"untrusted_evidence": value}, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
    prompt = encode_evidence(compact)
    for rows, text_size in [(3, 350), (2, 180)]:
        if len(prompt.encode()) <= min(10000, input_limit * 2):
            break
        compact = _shrink(compact, rows, text_size)
        prompt = encode_evidence(compact)
    fact_ids = {fact.get("id") for fact in facts.get("facts", []) if isinstance(fact.get("id"), str)}
    payload = {"model": model, "stream": False, "think": think, "format": output_schema(fact_ids),
               "options": {"temperature": 0.2, "num_predict": output_limit},
               "messages": [{"role": "system", "content": SYSTEM_INSTRUCTION + ONPREM_NOTE},
                            {"role": "user", "content": prompt}]}
    try:
        response = json_request(url, payload=payload, timeout=timeout, limit=262144)
    except RequestFailure as error:
        code = error.code if error.code in {"network_error", "timeout", "http_error", "invalid_json",
                                            "request_too_large", "response_too_large"} else "request_failed"
        raise RequestFailure("ollama_" + code, status=error.status) from None
    try:
        if response.get("done") is not True or response.get("done_reason", "stop") != "stop":
            raise ValueError()
        result = json.loads(response["message"]["content"])
        if not isinstance(result, dict) or set(result) != FIELDS:
            raise ValueError()
        usage = {"model": model}
        for local, remote in [("input_tokens", "prompt_eval_count"), ("output_tokens", "eval_count")]:
            value = response.get(remote, 0)
            if type(value) is not int or not 0 <= value <= 1000000:
                raise ValueError()
            usage[local] = value
        duration = response.get("total_duration", 0)
        usage["total_ms"] = int(duration / 1_000_000) if type(duration) is int and duration >= 0 else None
    except (KeyError, TypeError, ValueError, AttributeError):
        raise RequestFailure("ollama_response_invalid") from None
    return result, usage
