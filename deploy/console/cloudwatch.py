"""Fixed Grafana CloudWatch queries for the configured customer AWS resources."""
import datetime as dt
import math
import re
import time

from metrics import MAX_POINTS, MAX_RANGE, MAX_SERIES, finite


PATTERNS = {
    "region": r"[a-z]{2}(?:-[a-z]+)+-[0-9]",
    "load_balancer": r"app/[A-Za-z0-9-]{1,32}/[a-f0-9]{16}",
    "target_group": r"targetgroup/[A-Za-z0-9-]{1,32}/[a-f0-9]{16}",
    "auto_scaling_group": r"[A-Za-z0-9_.:/=-]{1,255}",
    "volume_id": r"vol-(?:[a-f0-9]{8}|[a-f0-9]{17})",
}

# The datasource, namespace, metric and dimension names are server-owned constants.
# This is the query model also provisioned in 12-msp-infra.json, not arbitrary PromQL or metric math.
DEFINITIONS = (
    ("aws_healthy_hosts", "AWS/ApplicationELB", "HealthyHostCount", "Minimum", "targets", "정상 대상", "count"),
    ("aws_unhealthy_hosts", "AWS/ApplicationELB", "UnHealthyHostCount", "Maximum", "targets", "비정상 대상", "count"),
    ("aws_requests", "AWS/ApplicationELB", "RequestCount", "Sum", "alb", "ALB 요청", "per_minute"),
    ("aws_latency_p95", "AWS/ApplicationELB", "TargetResponseTime", "p95", "alb", "대상 응답 P95", "milliseconds"),
    ("aws_errors", "AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "Sum", "alb", "ALB", "per_minute"),
    ("aws_errors", "AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "Sum", "alb", "Target", "per_minute"),
    ("aws_asg_desired", "AWS/AutoScaling", "GroupDesiredCapacity", "Maximum", "asg", "조회 ASG 목표", "count"),
    ("aws_asg_inservice", "AWS/AutoScaling", "GroupInServiceInstances", "Minimum", "asg", "조회 ASG 가동", "count"),
    ("aws_ebs_queue", "AWS/EBS", "VolumeQueueLength", "Average", "ebs", "EBS I/O 대기", "count"),
)
METRIC_IDS = tuple(dict.fromkeys(row[0] for row in DEFINITIONS))
REQUIRED = {"targets": ("load_balancer", "target_group"), "alb": ("load_balancer",),
            "asg": ("auto_scaling_group",), "ebs": ("volume_id",)}


class FrameError(ValueError):
    pass


def period_seconds(range_seconds):
    if type(range_seconds) is not int or not 300 <= range_seconds <= MAX_RANGE:
        raise ValueError("invalid_cloudwatch_range")
    # CloudWatch retains one-minute samples for 15 days and five-minute samples thereafter.
    resolution = 300 if range_seconds > 15 * 86400 else 60
    return max(resolution, math.ceil(range_seconds / ((MAX_POINTS - 2) * resolution)) * resolution)


def dimensions(config, group):
    if group == "targets":
        return {"LoadBalancer": [config["load_balancer"]], "TargetGroup": [config["target_group"]]}
    if group == "alb":
        return {"LoadBalancer": [config["load_balancer"]]}
    if group == "asg":
        return {"AutoScalingGroupName": [config["auto_scaling_group"]]}
    return {"VolumeId": [config["volume_id"]]}


def timestamp(value):
    if isinstance(value, bool):
        raise FrameError("invalid_frame_time")
    number = finite(value)
    if number is not None:
        return number / 1000 if abs(number) >= 100000000000 else number
    if isinstance(value, str):
        try:
            parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
            if parsed.tzinfo is not None:
                return parsed.timestamp()
        except ValueError:
            pass
    raise FrameError("invalid_frame_time")


def parse_frames(result, start, end, period, scale, source):
    if (not isinstance(result, dict) or result.get("error")
            or type(result.get("status", 200)) is not int or result.get("status", 200) >= 400):
        raise FrameError("cloudwatch_query_failed")
    frames = result.get("frames")
    if not isinstance(frames, list):
        raise FrameError("cloudwatch_frames_missing")
    if len(frames) > MAX_SERIES:
        raise FrameError("cloudwatch_series_limit")
    series = []
    for frame in frames:
        if not isinstance(frame, dict):
            raise FrameError("invalid_frame")
        fields = frame.get("schema", {}).get("fields")
        columns = frame.get("data", {}).get("values")
        if not isinstance(fields, list) or not isinstance(columns, list) or len(fields) != len(columns):
            raise FrameError("invalid_frame_columns")
        times = [index for index, field in enumerate(fields) if field.get("type") == "time"]
        if len(times) != 1:
            raise FrameError("invalid_frame_time_field")
        time_values = columns[times[0]]
        if not isinstance(time_values, list) or len(time_values) > MAX_POINTS:
            raise FrameError("cloudwatch_points_limit")
        for index, field in enumerate(fields):
            if field.get("type") != "number":
                continue
            if len(series) >= MAX_SERIES:
                raise FrameError("cloudwatch_series_limit")
            values = columns[index]
            labels = field.get("labels", {})
            if not isinstance(values, list) or len(values) != len(time_values) or not isinstance(labels, dict):
                raise FrameError("invalid_frame_values")
            labels = {str(key): str(value)[:256] for key, value in labels.items()}
            labels["source"] = source
            labels["field"] = str(field.get("name", source))[:256]
            points = []
            samples = []
            for raw_time, raw_value in zip(time_values, values):
                moment = timestamp(raw_time)
                value = finite(raw_value)
                if start <= moment <= end:
                    samples.append([moment, None if value is None else value * scale])
            for moment, value in sorted(samples):
                if points and moment - points[-1][0] > period * 1.5:
                    missing = points[-1][0] + period
                    while missing < moment:
                        points.append([missing, None])
                        missing += period
                        if len(points) >= MAX_POINTS:
                            raise FrameError("cloudwatch_points_limit")
                points.append([moment, value])
                if len(points) > MAX_POINTS:
                    raise FrameError("cloudwatch_points_limit")
            current = points[-1][1] if points and end - points[-1][0] <= max(180, 2 * period) else None
            series.append({"labels": labels, "points": points, "value": current})
    return series


def collect_aws(config, range_seconds, request):
    period = period_seconds(range_seconds)
    result = {"metrics": {name: {"state": "missing", "series": [], "message": "AWS 자원 설정 대기"} for name in METRIC_IDS},
              "sources": [{"name": "cloudwatch", "state": "missing", "message": "AWS 자원 설정 대기"}]}
    if not config:
        return result
    if not isinstance(config, dict) or set(config) - PATTERNS.keys() or any(
            value is not None and value != "" and (not isinstance(value, str) or not re.fullmatch(PATTERNS[key], value))
            for key, value in config.items()):
        return {"metrics": {name: {"state": "error", "series": [], "message": "AWS 설정 값 확인 필요"} for name in METRIC_IDS},
                "sources": [{"name": "cloudwatch", "state": "error", "message": "AWS 설정 값 확인 필요"}]}
    if not config.get("region"):
        return result
    end = int(time.time())
    start = math.floor((end - range_seconds) / period) * period
    payload = {"from": str(start * 1000), "to": str(end * 1000), "queries": []}
    selected = {}
    for index, (name, namespace, metric, statistic, group, label, unit) in enumerate(DEFINITIONS):
        if any(not config.get(key) for key in REQUIRED[group]):
            continue
        reference = "A" + str(index)
        selected[reference] = (name, label, 60 / period if unit == "per_minute" else 1000 if unit == "milliseconds" else 1)
        payload["queries"].append({"refId": reference, "datasource": {"type": "cloudwatch", "uid": "cloudwatch"},
                                   "queryMode": "Metrics", "metricQueryType": 0, "region": config["region"],
                                   "namespace": namespace, "metricName": metric, "dimensions": dimensions(config, group),
                                   "statistic": statistic, "period": str(period), "matchExact": True, "label": label,
                                   "format": "time_series", "maxDataPoints": MAX_POINTS, "intervalMs": period * 1000})
    if not selected:
        return result
    try:
        # Callback owns authenticated Grafana transport. Its exception text may contain provider details.
        response = request(payload)
    except Exception:
        for name, _, _ in selected.values():
            result["metrics"][name] = {"state": "error", "series": [], "message": "CloudWatch 조회 실패"}
        result["sources"] = [{"name": "cloudwatch", "state": "error", "message": "CloudWatch 연결·조회 권한 확인 필요"}]
        return result
    responses = response.get("results", {}) if isinstance(response, dict) else {}
    expected = {}
    collected = {}
    errors = set()
    for reference, (name, label, scale) in selected.items():
        expected[name] = expected.get(name, 0) + 1
        try:
            parsed = parse_frames(responses.get(reference), start, end, period, scale, label)
            collected.setdefault(name, []).extend(parsed)
        except (FrameError, TypeError, ValueError, KeyError, AttributeError, OverflowError):
            errors.add(name)
    for name in expected:
        series = collected.get(name, [])
        if name in errors:
            result["metrics"][name] = {"state": "error", "series": series, "message": "CloudWatch 지표 조회 실패"}
        else:
            state = "ok" if any(item["value"] is not None for item in series) else "missing"
            result["metrics"][name] = {"state": state, "series": series,
                                       "message": "최근 표본 없음" if state == "missing" else "조회 완료"}
    missing_config = any(name not in expected for name in METRIC_IDS)
    state = "error" if errors else "missing" if missing_config else "ok"
    message = "CloudWatch 지표 일부 조회 실패" if errors else "일부 AWS 자원 설정 대기" if missing_config else f"CloudWatch 조회 완료 · {period}초 집계"
    result["sources"] = [{"name": "cloudwatch", "state": state, "message": message, "periodSeconds": period}]
    return result
