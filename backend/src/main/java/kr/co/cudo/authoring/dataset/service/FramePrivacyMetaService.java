package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyBulkItem;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.dto.FramePrivacyMetaUpdateRequest;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3 — 프레임 개인정보 메타(익명/가명/개인정보 포함여부) 조회·저장 서비스.
 *
 * <p>인가는 {@link LabelAccessGuard#verifyAndGet}(REVIEWER 통과 / WORKER 본인 배정 프레임만 / 그 외 403,
 * 미존재 404) 재사용으로 IDOR(CWE-639)를 최우선 차단한다. 검수 완료(APPROVED) 후 수정 시에만
 * {@code TASK_MODIFIED}(META_UPDATED) 를 발행한다(라벨/메타 경로 동일 가드). 동일 rawSn 다건은
 * {@code ControlNotifyDebouncer} 60초 윈도우가 프레임 목록을 모아 1회 통지로 코얼레스한다(MED#6).
 *
 * <h3>프리필(파생) 원천 — 현행 로직 유지(관제 클립메타 원천화는 후속 백로그)</h3>
 * <ul>
 *   <li>pseudonymity : 영상 개인정보 유형 == PSDO 이면 "Y" 아니면 "N"</li>
 *   <li>privacyIncluded : 영상 PRVC_YN</li>
 *   <li>anonymity : 영상 개인정보 유형 == ANONY 이면 "Y" 아니면 "N" (프레임 판단 프리필 표시용)</li>
 * </ul>
 * 저장값(수동)이 있으면 필드별로 그 값을 우선하고, 없으면 위 파생값을 반환한다(effective value).
 *
 * <p><b>★#1 — anonymity 는 export 를 덮지 않는다</b>: 저장한 anonymity 는 화면 표시·기록(라벨러 판단)용이며,
 * 학습데이터 export 의 anonymity 는 산출 종류(원본=N/비식별=Y)로 결정된다({@code NiaJsonBuilder} 참조).
 * export 수동 우선은 pseudonymity/privacyIncluded 에만 적용된다.
 *
 * <p>보안 — 개인정보 가능성이 있어 로그에 입력 원문(Y/N 판단 근거 등)은 남기지 않고 srcSn·rawSn 만 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class FramePrivacyMetaService {

    private final LabelAccessGuard guard;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 프레임 개인정보 메타 조회 — 수동값 우선, 미저장 필드는 파생 프리필. */
    public FramePrivacyMetaResponse get(Long srcSn, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        LsDataRaw raw = loadRaw(src.getRawSn());
        return toEffective(src, raw);
    }

    /** 단건 저장/수정 — 전체 교체(3필드 함께). null 필드는 수동값 삭제(파생 폴백). */
    @Transactional("controlTransactionManager")
    public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        applyAndNotify(src, req.anonymity(), req.pseudonymity(), req.privacyIncluded(), actor);
        return toEffective(src, loadRaw(src.getRawSn()));
    }

    /**
     * 벌크 저장 — 인가·저장을 벌크화(MED-1, N+1 제거)하되 IDOR 방어·디바운스 시맨틱은 불변으로 유지한다.
     *
     * <p>성능(시맨틱 불변):
     * <ul>
     *   <li><b>프레임 벌크 조회</b> — 모든 srcSn 을 {@link LsDataSrcRepository#findAllById} 로 한 번에 로드한다
     *       (프레임 수만큼 {@code verifyAndGet}→findById 하던 N+1 제거). 미존재 srcSn 은 조회 결과에 빠지므로
     *       {@code guard.verifyAndGet} 과 동일한 404 로 거부한다.</li>
     *   <li><b>인가 1회/rawSn</b> — WORKER 배정 검사를 프레임마다 반복하지 않고 rawSn distinct 집합 기준
     *       {@link LabelAccessGuard#verifyRawAccess} 로 1회씩만 수행한다. 동일 프레임의 rawSn 은 모두 같은
     *       인가 결과이므로 프레임별 verifyAndGet 과 결과가 동치다(타 영상 403·포털 403 그대로).
     *       평가 순서(항목 순회 중 미존재=404 먼저, 그다음 rawSn 인가=403)도 기존과 동일하게 보존한다.</li>
     *   <li><b>saveAll</b> — 변경 엔티티를 모아 {@link LsDataSrcRepository#saveAll} 로 1회 flush.</li>
     * </ul>
     *
     * <p>불변(현행 유지): 항목별 인가 정확성(미존재 404·타인 403), 벌크 원자성(@Transactional 부분 실패 전체 롤백),
     * 검수 완료(APPROVED) 후에만 각 프레임 META_UPDATED 발행 → 다운스트림 디바운서가 rawSn 단위 1회 통지로
     * 코얼레스(MED#6). raw/APPROVED 조회는 rawSn 단위 캐시로 N+1 을 회피한다.
     */
    @Transactional("controlTransactionManager")
    public List<FramePrivacyMetaResponse> updateBulk(List<FramePrivacyBulkItem> items, TokenClaims actor) {
        // 1) 프레임 벌크 조회 (findById N회 → findAllById 1회). 미존재는 결과에서 누락 → 항목별 404 로 거부.
        Map<Long, LsDataSrc> srcCache = new HashMap<>();
        for (LsDataSrc src : srcRepository.findAllById(
                items.stream().map(FramePrivacyBulkItem::srcSn).toList())) {
            srcCache.put(src.getSrcSn(), src);
        }

        Set<Long> authorizedRawSns = new HashSet<>();
        Map<Long, LsDataRaw> rawCache = new HashMap<>();
        Map<Long, Boolean> approvedCache = new HashMap<>();
        List<LsDataSrc> toSave = new ArrayList<>(items.size());
        List<FramePrivacyMetaResponse> result = new ArrayList<>(items.size());
        for (FramePrivacyBulkItem item : items) {
            // 미존재 프레임 → 404 (guard.verifyAndGet 과 동일 — IDOR 방어 보존).
            LsDataSrc src = srcCache.get(item.srcSn());
            if (src == null) {
                throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
            }
            Long rawSn = src.getRawSn();
            // 인가는 rawSn distinct 집합 기준 1회씩만(WORKER 배정 검사 중복 제거). 타 영상/포털 → 403.
            if (authorizedRawSns.add(rawSn)) {
                guard.verifyRawAccess(rawSn, actor);
            }
            src.updatePrivacyMeta(item.anonymity(), item.pseudonymity(), item.privacyIncluded());
            toSave.add(src);
            if (isReviewApprovedCached(rawSn, approvedCache)) {
                eventPublisher.publishEvent(new TaskModifiedEvent(
                        rawSn, src.getSrcSn(), ChangeType.META_UPDATED, guard.parseUserNo(actor.sub())));
            }
            LsDataRaw raw = rawCache.computeIfAbsent(rawSn, this::loadRaw);
            result.add(toEffective(src, raw));
        }
        srcRepository.saveAll(toSave);
        log.info("[FramePrivacyMeta] bulk-updated count={} rawSns={}", items.size(), rawCache.keySet().size());
        return result;
    }

    // ---------- 내부 ----------

    private void applyAndNotify(LsDataSrc src, String anonymity, String pseudonymity, String privacyIncluded,
                                TokenClaims actor) {
        Long rawSn = src.getRawSn();
        src.updatePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
        srcRepository.save(src);
        // 본문(판단값) 미출력 — srcSn·rawSn 만 (CWE-359)
        log.info("[FramePrivacyMeta] updated srcSn={} rawSn={}", src.getSrcSn(), rawSn);
        if (isReviewApproved(rawSn)) {
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, src.getSrcSn(), ChangeType.META_UPDATED, guard.parseUserNo(actor.sub())));
        }
    }

    /** 저장(수동)값 우선, 미저장 필드는 파생 프리필로 채운 유효값 응답. */
    private FramePrivacyMetaResponse toEffective(LsDataSrc src, LsDataRaw raw) {
        String anonymity = firstNonBlank(src.getAnonyInclYn(), deriveAnonymity(raw));
        String pseudonymity = firstNonBlank(src.getPsdoInclYn(), derivePseudonymity(raw));
        String privacyIncluded = firstNonBlank(src.getPrvcInclYn(), derivePrivacyIncluded(raw));
        return new FramePrivacyMetaResponse(src.getSrcSn(), anonymity, pseudonymity, privacyIncluded);
    }

    private String deriveAnonymity(LsDataRaw raw) {
        if (raw == null) {
            return null;
        }
        return LsDataRaw.PRVC_TYPE_ANONY.equals(raw.getPrvcTypeCd()) ? "Y" : "N";
    }

    private String derivePseudonymity(LsDataRaw raw) {
        if (raw == null) {
            return null;
        }
        return LsDataRaw.PRVC_TYPE_PSDO.equals(raw.getPrvcTypeCd()) ? "Y" : "N";
    }

    private String derivePrivacyIncluded(LsDataRaw raw) {
        return raw == null ? null : raw.getPrvcYn();
    }

    private LsDataRaw loadRaw(Long rawSn) {
        return videoRepository.findById(rawSn).orElse(null);
    }

    private boolean isReviewApprovedCached(Long rawSn, Map<Long, Boolean> cache) {
        return cache.computeIfAbsent(rawSn, this::isReviewApproved);
    }

    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    private static String firstNonBlank(String primary, String fallback) {
        return (primary == null || primary.isBlank()) ? fallback : primary;
    }
}
