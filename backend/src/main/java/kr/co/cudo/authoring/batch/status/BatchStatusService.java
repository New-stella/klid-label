package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.batch.dto.BatchStageProgress;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 배치 단계별 in-memory 상태 추적 (Phase 5).
 *  - 멀티스레드 안전: ConcurrentHashMap + atomic counter.
 *  - 메모리 누수 방지: 최대 N건 유지 (오래된 entry 제거).
 *  - DB sync 는 별도 Phase 4 BatchStatusController 와 결합 (본 Phase 는 in-memory 만).
 */
@Slf4j
@Service
public class BatchStatusService {

    /** 메모리 한도 — 최대 보관 영상 수. 초과 시 가장 오래된 항목 제거. */
    static final int MAX_ENTRIES = 10_000;

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public void markStage(Long rawSn, BatchStage stage) {
        if (rawSn == null || stage == null) return;
        entries.compute(rawSn, (k, prev) -> {
            Entry e = prev == null ? new Entry(rawSn) : prev;
            e.stage = stage;
            e.lastUpdatedAt = LocalDateTime.now();
            if (e.startedAt == null) {
                e.startedAt = e.lastUpdatedAt;
            }
            return e;
        });
        evictIfNeeded();
    }

    public void markCompleted(Long rawSn) {
        markStage(rawSn, BatchStage.COMPLETED);
    }

    public void markFailed(Long rawSn, Throwable cause) {
        if (rawSn == null) return;
        entries.compute(rawSn, (k, prev) -> {
            Entry e = prev == null ? new Entry(rawSn) : prev;
            e.stage = BatchStage.FAILED;
            e.lastUpdatedAt = LocalDateTime.now();
            if (e.startedAt == null) e.startedAt = e.lastUpdatedAt;
            e.errorMessage = cause == null ? "unknown" : cause.getClass().getSimpleName();
            e.retryCount.incrementAndGet();
            return e;
        });
        evictIfNeeded();
    }

    public BatchStage currentStage(Long rawSn) {
        Entry e = entries.get(rawSn);
        return e == null ? BatchStage.PENDING : e.stage;
    }

    public int retryCount(Long rawSn) {
        Entry e = entries.get(rawSn);
        return e == null ? 0 : e.retryCount.get();
    }

    /** 최근 N건을 lastUpdatedAt DESC 로 반환. */
    public List<BatchStageProgress> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_ENTRIES));
        List<Entry> snapshot = new ArrayList<>(entries.values());
        snapshot.sort(Comparator.comparing((Entry e) -> e.lastUpdatedAt).reversed());
        return snapshot.stream()
                .limit(safeLimit)
                .map(this::toDto)
                .toList();
    }

    private BatchStageProgress toDto(Entry e) {
        return new BatchStageProgress(
                e.rawSn,
                e.stage.name(),
                e.startedAt,
                e.lastUpdatedAt,
                e.retryCount.get(),
                e.errorMessage
        );
    }

    private void evictIfNeeded() {
        if (entries.size() <= MAX_ENTRIES) return;
        // 오래된 항목 제거 — 단일 스캔으로 충분.
        List<Map.Entry<Long, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparing((Map.Entry<Long, Entry> en) -> en.getValue().lastUpdatedAt));
        int over = entries.size() - MAX_ENTRIES;
        for (int i = 0; i < over && i < sorted.size(); i++) {
            entries.remove(sorted.get(i).getKey());
        }
    }

    private static final class Entry {
        final Long rawSn;
        volatile BatchStage stage = BatchStage.PENDING;
        volatile LocalDateTime startedAt;
        volatile LocalDateTime lastUpdatedAt;
        final AtomicInteger retryCount = new AtomicInteger(0);
        volatile String errorMessage;

        Entry(Long rawSn) { this.rawSn = rawSn; }
    }
}
