package kr.co.cudo.authoring.controlnotify.debounce;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.controlnotify.service.FrameChangeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ControlNotifyDebounceStore} 의 DB 구현 — 디바운스 윈도우를 {@code LS_MON_NOTI_ACML} 에
 * 영속화해 <b>2노드 Active-Active 에서 한 번만 flush</b> 되게 한다 (Phase 9-C).
 *
 * <h3>동시성 설계</h3>
 * <ul>
 *   <li><b>축적</b> — 열린 윈도우가 없으면 {@code INSERT … ON CONFLICT DO NOTHING}(부분 유니크 추론)으로
 *       원자 개시하고, 반드시 존재하게 된 행을 {@code SELECT … FOR UPDATE} 로 잠가 JSON 머지를
 *       직렬화한다(read-modify-write lost update 차단, CWE-362).
 *       {@code BatchRetryQueue#enqueueIfRetryable} 와 동일 패턴이다.</li>
 *   <li><b>클레임</b> — 상태 전이 자체를 조건부 UPDATE 로 만들어 DB 가 직렬화한다. 한 노드만 1행을 얻는다.</li>
 * </ul>
 *
 * <p>모든 메서드는 호출부가 활성 트랜잭션을 갖지 않는 경로(AFTER_COMMIT 리스너 / 전용 flush 스케줄러
 * 스레드)에서 불리므로 {@code REQUIRES_NEW} 로 독립 트랜잭션을 열어 즉시 커밋한다.
 *
 * <h3>저장 내용</h3>
 * 라벨/메타 <b>본문은 담지 않는다</b> — 식별자(rawSn·srcSn)와 변경 종류만이라 PII 표면이 없다
 * (관제 전송 계약 자체가 "수정 요약만"이다). 역직렬화는 명시 타입 고정이라 다형성 역직렬화 표면도
 * 없다(CWE-502).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JpaControlNotifyDebounceStore implements ControlNotifyDebounceStore {

    private static final TypeReference<Map<String, Map<String, List<String>>>> PAYLOAD_TYPE =
            new TypeReference<>() {
            };

    /** 프레임 단위 변경 묶음 키. */
    private static final String KEY_FRAMES = "frames";
    /** 영상 단위 변경(srcSn=null) 묶음 키. 프레임 키 공간과 섞이지 않도록 별도 맵에 둔다. */
    private static final String KEY_VIDEO = "video";
    /** 영상 단위 변경 종류를 담는 고정 슬롯(형태를 프레임 맵과 동일하게 유지해 파싱 타입을 하나로 묶는다). */
    private static final String VIDEO_SLOT = "*";

    private final LsMonNotiAcmlRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void accumulate(Long rawSn, Long srcSn, String changeType, boolean exportRegenerated) {
        if (rawSn == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LsMonNotiAcml window = openWindow(rawSn, now);
        if (window == null) {
            // 열린 윈도우 확보 실패 — 통지가 조용히 사라지지 않도록 반드시 남긴다(무음 드롭 금지).
            log.error("[ControlNotifyDebounce] window open failed — modification not accumulated rawSn={}", rawSn);
            return;
        }
        Map<String, Map<String, List<String>>> payload = parse(window.getChgDtlCn(), rawSn);
        merge(payload, srcSn, changeType);
        window.applyAccumulation(write(payload), exportRegenerated);
        repository.save(window);
    }

    /**
     * 열린 윈도우를 확보한다 — 없으면 원자 INSERT 후 잠금 로드.
     *
     * <p>INSERT 와 SELECT 사이에 다른 노드가 그 윈도우를 클레임(PENDING→FLUSHING)하면 잠금 로드가
     * 비게 되므로 한 번 더 시도한다. 두 번째도 비면 극단적 경합이므로 호출부가 오류로 남긴다.
     */
    private LsMonNotiAcml openWindow(Long rawSn, LocalDateTime now) {
        repository.insertPendingIfAbsent(rawSn, now);
        Optional<LsMonNotiAcml> found = repository.findPendingForUpdate(rawSn);
        if (found.isPresent()) {
            return found.get();
        }
        repository.insertPendingIfAbsent(rawSn, now);
        return repository.findPendingForUpdate(rawSn).orElse(null);
    }

    @Override
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> findFlushableIds(LocalDateTime windowCutoff, LocalDateTime leaseCutoff, int limit) {
        return repository.findFlushableAnchors(windowCutoff, leaseCutoff, Math.max(1, limit));
    }

    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<DebounceWindow> claim(Long acmlSn, LocalDateTime windowCutoff, LocalDateTime leaseCutoff) {
        if (acmlSn == null
                || repository.claimForFlush(acmlSn, windowCutoff, leaseCutoff, LocalDateTime.now()) != 1) {
            return Optional.empty();
        }
        return repository.findById(acmlSn).map(this::toWindow);
    }

    @Override
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void complete(Long acmlSn) {
        if (acmlSn != null) {
            repository.deleteFlushed(acmlSn);
        }
    }

    private DebounceWindow toWindow(LsMonNotiAcml row) {
        Map<String, Map<String, List<String>>> payload = parse(row.getChgDtlCn(), row.getRawSn());
        List<FrameChangeSet> frameChanges = new ArrayList<>();
        payload.getOrDefault(KEY_FRAMES, Map.of()).forEach((srcSn, types) -> {
            Long parsed = parseSrcSn(srcSn, row.getRawSn());
            if (parsed != null) {
                frameChanges.add(new FrameChangeSet(parsed, new LinkedHashSet<>(types)));
            }
        });
        Set<String> videoLevel = new LinkedHashSet<>(
                payload.getOrDefault(KEY_VIDEO, Map.of()).getOrDefault(VIDEO_SLOT, List.of()));
        return new DebounceWindow(row.getNotiAcmlSn(), row.getRawSn(),
                frameChanges, videoLevel, row.isExportRegenerated());
    }

    private Long parseSrcSn(String key, Long rawSn) {
        try {
            return Long.valueOf(key);
        } catch (NumberFormatException e) {
            // 저장은 우리가 하므로 정상 경로에서는 발생하지 않는다. 파일명 "frame-null" 류 오염을 막기 위해 버린다.
            log.warn("[ControlNotifyDebounce] invalid frame key dropped rawSn={}", rawSn);
            return null;
        }
    }

    /** 변경 1건을 누적 맵에 머지한다. changeType 이 null 이면 축적할 내용이 없다(윈도우는 이미 열렸다). */
    private void merge(Map<String, Map<String, List<String>>> payload, Long srcSn, String changeType) {
        if (changeType == null) {
            return;
        }
        String group = srcSn == null ? KEY_VIDEO : KEY_FRAMES;
        String slot = srcSn == null ? VIDEO_SLOT : String.valueOf(srcSn);
        List<String> types = payload
                .computeIfAbsent(group, k -> new LinkedHashMap<>())
                .computeIfAbsent(slot, k -> new ArrayList<>());
        if (!types.contains(changeType)) {
            types.add(changeType);
        }
    }

    /**
     * 누적 JSON 파싱 — 명시 타입만 역직렬화한다(CWE-502).
     *
     * <p>파싱 실패 시 빈 맵으로 복구한다. 실패해도 flush 자체는 나가야 하며(윈도우 소실 방지),
     * 페이로드 원문은 로그에 남기지 않는다(CWE-117/359).
     */
    private Map<String, Map<String, List<String>>> parse(String json, Long rawSn) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Map<String, List<String>>> parsed = objectMapper.readValue(json, PAYLOAD_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            log.error("[ControlNotifyDebounce] accumulated payload parse failed rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
            return new LinkedHashMap<>();
        }
    }

    private String write(Map<String, Map<String, List<String>>> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // 직렬화 실패는 우리 자료구조 문제라 발생하지 않지만, 실패 시 윈도우를 비워 flush 는 살린다.
            log.error("[ControlNotifyDebounce] accumulated payload write failed reason={}",
                    e.getClass().getSimpleName());
            return "{}";
        }
    }
}
