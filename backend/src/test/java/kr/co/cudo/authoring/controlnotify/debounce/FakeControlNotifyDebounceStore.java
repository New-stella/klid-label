package kr.co.cudo.authoring.controlnotify.debounce;

import kr.co.cudo.authoring.controlnotify.service.FrameChangeSet;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ControlNotifyDebounceStore} 인메모리 페이크 — 단위 테스트용.
 *
 * <p>DB 구현({@code JpaControlNotifyDebounceStore})과 <b>동일한 계약</b>을 지킨다:
 * <ul>
 *   <li>영상당 열린 윈도우는 최대 1개(부분 유니크 대응).</li>
 *   <li>클레임은 원자적이다 — 동시에 들어와도 정확히 한 호출만 스냅샷을 받는다(S6/크로스노드 대응).
 *       DB 는 row lock 으로, 여기서는 {@code synchronized} 로 보장한다.</li>
 *   <li>{@code complete} 전에는 행이 사라지지 않는다 — 발송 실패분은 임차 만료 후 재클레임된다.</li>
 * </ul>
 *
 * <p>실 DB 정합(2노드 클레임·크래시 회수)은 {@code ControlNotifyDebounceCrossNodeIT} 가 Testcontainers 로
 * 검증하고, 여기서는 디바운서의 행위(축적·만료·발송 분기·격리)를 빠르게 검증한다.
 */
public class FakeControlNotifyDebounceStore implements ControlNotifyDebounceStore {

    /** 저장 행 — DB 컬럼과 1:1 대응. */
    private static final class Row {
        final long acmlSn;
        final long rawSn;
        String sttsCd = LsMonNotiAcml.STATUS_PENDING;
        LocalDateTime regDt = LocalDateTime.now();
        LocalDateTime mdfcnDt = LocalDateTime.now();
        final Map<Long, Set<String>> frameChanges = new LinkedHashMap<>();
        final Set<String> videoLevelChangeTypes = new LinkedHashSet<>();
        boolean exportRegenerated;

        Row(long acmlSn, long rawSn) {
            this.acmlSn = acmlSn;
            this.rawSn = rawSn;
        }
    }

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, Row> rows = new LinkedHashMap<>();

    @Override
    public synchronized void accumulate(Long rawSn, Long srcSn, String changeType, boolean exportRegenerated) {
        if (rawSn == null) {
            return;
        }
        Row row = openRow(rawSn);
        if (exportRegenerated) {
            row.exportRegenerated = true;
        }
        row.mdfcnDt = LocalDateTime.now();
        if (changeType == null) {
            return; // 축적할 내용은 없지만 윈도우는 열린다(구 구현과 동일).
        }
        if (srcSn == null) {
            row.videoLevelChangeTypes.add(changeType);
            return;
        }
        row.frameChanges.computeIfAbsent(srcSn, k -> new LinkedHashSet<>()).add(changeType);
    }

    private Row openRow(Long rawSn) {
        return rows.values().stream()
                .filter(r -> r.rawSn == rawSn && LsMonNotiAcml.STATUS_PENDING.equals(r.sttsCd))
                .findFirst()
                .orElseGet(() -> {
                    Row created = new Row(sequence.incrementAndGet(), rawSn);
                    rows.put(created.acmlSn, created);
                    return created;
                });
    }

    @Override
    public synchronized List<Long> findFlushableIds(LocalDateTime windowCutoff, LocalDateTime leaseCutoff, int limit) {
        return rows.values().stream()
                .filter(r -> isFlushable(r, windowCutoff, leaseCutoff))
                .sorted(Comparator.comparing(r -> r.regDt))
                .limit(Math.max(1, limit))
                .map(r -> r.acmlSn)
                .toList();
    }

    private boolean isFlushable(Row row, LocalDateTime windowCutoff, LocalDateTime leaseCutoff) {
        if (LsMonNotiAcml.STATUS_PENDING.equals(row.sttsCd)) {
            return !row.regDt.isAfter(windowCutoff);
        }
        return !row.mdfcnDt.isAfter(leaseCutoff);
    }

    @Override
    public synchronized Optional<DebounceWindow> claim(Long acmlSn, LocalDateTime windowCutoff,
                                                       LocalDateTime leaseCutoff) {
        Row row = rows.get(acmlSn);
        if (row == null || !isFlushable(row, windowCutoff, leaseCutoff)) {
            return Optional.empty();
        }
        row.sttsCd = LsMonNotiAcml.STATUS_FLUSHING;
        row.mdfcnDt = LocalDateTime.now();
        return Optional.of(toWindow(row));
    }

    @Override
    public synchronized void complete(Long acmlSn) {
        Row row = rows.get(acmlSn);
        if (row != null && LsMonNotiAcml.STATUS_FLUSHING.equals(row.sttsCd)) {
            rows.remove(acmlSn);
        }
    }

    @Override
    public synchronized boolean hasOpenWindow(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return rows.values().stream().anyMatch(r -> r.rawSn == rawSn);
    }

    private DebounceWindow toWindow(Row row) {
        List<FrameChangeSet> changes = new ArrayList<>();
        row.frameChanges.forEach((srcSn, types) -> changes.add(new FrameChangeSet(srcSn, types)));
        return new DebounceWindow(row.acmlSn, row.rawSn, changes,
                Set.copyOf(row.videoLevelChangeTypes), row.exportRegenerated);
    }

    // ------------------------------------------------------------------ 테스트 헬퍼

    /** 열린(PENDING) 윈도우의 rawSn 집합 — 구 테스트의 {@code windows.keySet()} 대응. */
    public synchronized Set<Long> openRawSns() {
        Set<Long> result = new LinkedHashSet<>();
        rows.values().stream()
                .filter(r -> LsMonNotiAcml.STATUS_PENDING.equals(r.sttsCd))
                .forEach(r -> result.add(r.rawSn));
        return result;
    }

    /** 저장된(열린 + 발송 중) 전체 행 수 — 실패분이 보존되는지 확인할 때 쓴다. */
    public synchronized int totalRows() {
        return rows.size();
    }

    /** 발송 실패로 {@code FLUSHING} 에 남은 rawSn 집합(임차 만료 시 재클레임 대상). */
    public synchronized Set<Long> flushingRawSns() {
        Set<Long> result = new LinkedHashSet<>();
        rows.values().stream()
                .filter(r -> LsMonNotiAcml.STATUS_FLUSHING.equals(r.sttsCd))
                .forEach(r -> result.add(r.rawSn));
        return result;
    }

    /** 해당 영상의 열린 윈도우 스냅샷(없으면 null) — 축적 내용 단언용. */
    public synchronized DebounceWindow snapshot(Long rawSn) {
        return rows.values().stream()
                .filter(r -> r.rawSn == rawSn && LsMonNotiAcml.STATUS_PENDING.equals(r.sttsCd))
                .findFirst()
                .map(this::toWindow)
                .orElse(null);
    }

    /** 해당 영상의 열린 윈도우를 만료시킨다(구 테스트의 {@code createdAt} 되감기 대응). */
    public synchronized void expire(Long rawSn) {
        rows.values().stream()
                .filter(r -> r.rawSn == rawSn && LsMonNotiAcml.STATUS_PENDING.equals(r.sttsCd))
                .forEach(r -> r.regDt = LocalDateTime.now().minusSeconds(70));
    }

    /** FLUSHING 행의 임차를 만료시킨다 — 클레임 노드가 죽은 상황 재현. */
    public synchronized void expireLease(Long rawSn, Duration age) {
        rows.values().stream()
                .filter(r -> r.rawSn == rawSn && LsMonNotiAcml.STATUS_FLUSHING.equals(r.sttsCd))
                .forEach(r -> r.mdfcnDt = LocalDateTime.now().minus(age));
    }
}
