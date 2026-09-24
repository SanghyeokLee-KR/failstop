package kr.exchange.admin.msp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Alertmanager 활성 경보를 주기적으로 읽어 사건 표에 맞춘다(설계 6-1).
 *
 * 해제는 표가 아니라 폴링 응답으로 판정한다. 성공한 폴링에서 연속으로 보이지 않은 기간이 resolve-after 를
 * 넘고, 그 기간에 Alertmanager 가 다시 시작하지 않았을 때만 닫는다. Alertmanager 는 경보를 메모리에만 두므로
 * 재시작 직후 빈 목록을 성공으로 돌려준다. 폴링이 실패하면 아무것도 닫지 않고 부재 기간도 처음부터 다시 센다.
 */
public class IncidentPoller {

    private static final Logger log = LoggerFactory.getLogger(IncidentPoller.class);

    /** 마지막 폴링 결과. 경보 조회 API 는 Alertmanager 를 매번 부르지 않고 이것을 읽는다. */
    public record Poll(Instant lastSuccessAt, List<AlertmanagerClient.Alert> alerts, Set<String> heartbeats,
                       int writeFailures, String failure, Instant failedAt) {

        public boolean healthy() {
            return failure == null && lastSuccessAt != null;
        }
    }

    private record Key(String fingerprint, Instant startedAt) {
    }

    private final AlertmanagerClient alertmanager;
    private final IncidentStore store;
    private final Clock clock;
    private final Duration resolveAfter;
    private final Set<String> heartbeatNames;
    private final Map<Key, Instant> missingSince = new HashMap<>();
    private volatile Poll last = new Poll(null, List.of(), Set.of(), 0, "not_polled", null);
    private ScheduledExecutorService scheduler;

    public IncidentPoller(AlertmanagerClient alertmanager, IncidentStore store, Clock clock, Duration resolveAfter,
                          List<String> heartbeatNames) {
        this.alertmanager = alertmanager;
        this.store = store;
        this.clock = clock;
        this.resolveAfter = resolveAfter;
        this.heartbeatNames = Set.copyOf(heartbeatNames);
    }

    public void start(ScheduledExecutorService executor, Duration interval) {
        this.scheduler = executor;
        executor.scheduleWithFixedDelay(this::pollQuietly, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 사람의 보류·해제 직후 화면 반영을 앞당긴다. 폴링 스레드에서 돈다. */
    public void pollSoon() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.execute(this::pollQuietly);
        }
    }

    public Poll last() {
        return last;
    }

    public boolean isHeartbeat(AlertmanagerClient.Alert alert) {
        return heartbeatNames.contains(alert.labels().getOrDefault("alertname", ""));
    }

    /** 등급 라벨이 정확히 critical·warning·info 가 아니면 other 다. 모르는 등급을 약한 등급으로 읽지 않는다. */
    public static String severity(Map<String, String> labels) {
        String raw = labels.get("severity");
        return "critical".equals(raw) || "warning".equals(raw) || "info".equals(raw) ? raw : "other";
    }

    /** 경보 한 건의 사건을 맞추고 id 를 돌려준다. 폴링과 사람의 보류가 같은 지문을 동시에 쓰지 않게 폴링과 직렬화한다. */
    public synchronized long record(AlertmanagerClient.Alert alert) {
        return store.recordFiring(alert, severity(alert.labels()), clock.instant());
    }

    private void pollQuietly() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("경보 폴링 처리 실패: {}", e.toString());
        }
    }

    public synchronized void pollOnce() {
        Instant now = clock.instant();
        List<AlertmanagerClient.Alert> all;
        try {
            all = alertmanager.alerts();
        } catch (SourceException e) {
            fail(e.reason(), now);
            return;
        }
        List<AlertmanagerClient.Alert> alerts = new ArrayList<>();
        Set<String> heartbeats = new HashSet<>();
        Set<Key> present = new HashSet<>();
        int writeFailures = 0;
        for (AlertmanagerClient.Alert alert : all) {
            if (isHeartbeat(alert)) {
                heartbeats.add(alert.fingerprint());
                continue;
            }
            alerts.add(alert);
            present.add(new Key(alert.fingerprint(), alert.startsAt()));
            if (!IncidentStore.FINGERPRINT.matcher(alert.fingerprint()).matches()) {
                writeFailures++;
                continue;
            }
            try {
                store.recordFiring(alert, severity(alert.labels()), now);
            } catch (RuntimeException e) {
                writeFailures++;
                log.warn("경보 사건 쓰기 실패 fingerprint={}: {}", alert.fingerprint(), e.toString());
            }
        }
        writeFailures += resolveMissing(present, now);
        last = new Poll(now, List.copyOf(alerts), Set.copyOf(heartbeats), writeFailures, null, null);
    }

    private int resolveMissing(Set<Key> present, Instant now) {
        List<IncidentStore.Episode> open = store.openEpisodes();
        Set<Key> openKeys = new HashSet<>();
        Instant alertmanagerStart = null;
        boolean startUnknown = false;
        int failures = 0;
        for (IncidentStore.Episode episode : open) {
            Key key = new Key(episode.fingerprint(), episode.startedAt());
            openKeys.add(key);
            if (present.contains(key)) {
                missingSince.remove(key);
                continue;
            }
            Instant first = missingSince.computeIfAbsent(key, k -> now);
            if (Duration.between(first, now).compareTo(resolveAfter) < 0 || startUnknown) {
                continue;
            }
            if (alertmanagerStart == null) {
                try {
                    alertmanagerStart = alertmanager.startedAt();
                } catch (SourceException e) {
                    // 시작 시각을 모르면 재시작 여부를 모른다. 이번 폴링에서는 아무것도 닫지 않는다.
                    startUnknown = true;
                    continue;
                }
            }
            if (alertmanagerStart.isAfter(first)) {
                missingSince.put(key, now);
                continue;
            }
            try {
                store.resolve(episode.id(), first);
                missingSince.remove(key);
            } catch (RuntimeException e) {
                failures++;
                log.warn("경보 사건 해제 쓰기 실패 id={}: {}", episode.id(), e.toString());
            }
        }
        missingSince.keySet().retainAll(openKeys);
        return failures;
    }

    private void fail(String reason, Instant now) {
        missingSince.clear();
        Poll previous = last;
        last = new Poll(previous.lastSuccessAt(), previous.alerts(), previous.heartbeats(),
                previous.writeFailures(), reason, now);
    }
}
