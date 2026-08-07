package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
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
import kr.co.cudo.authoring.dataset.dto.VideoPrivacyMetaResponse;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
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
 * <h3>★ 적재 기본값 (2026-08-04) — 프리필이 타는 경로가 좁아졌다</h3>
 * <p>프레임 생성 시점에 비식별 3필드가 실제 값({@code Y}/{@code N}/{@code N})으로 INSERT 된다
 * ({@code LsDataSrc} 팩토리). 따라서 신규 프레임은 항상 저장값을 갖고 프리필을 타지 않는다. 프리필이
 * 남는 경로는 <b>레거시 프레임(이 변경 이전에 생성된 프레임) 하나뿐</b>이며, 그 <b>안전망</b>으로
 * 아래 프리필을 존치한다.
 * ⚠ <b>의미 축소(2026-08-04) — 그러나 존치한다</b>: 프리필·{@code *Source} 는 이제 <b>레거시 프레임</b>
 * 표시용이다. "적재 기본값"과 "사람이 고른 값"은 구분하지 않는 것이 확정 정책이다.
 * ⚠⚠ <b>구 근거 폐기(2026-08-04)</b>: 구 서술은 남는 경로에 <b>"② 비식별 누락 신고 리셋 직후(= 사람이
 * 다시 판정해야 함)"</b> 를 들고 "리셋이 명시적 NULL 을 쓰므로 {@code DERIVED} 가 '아직 재판정하지
 * 않았다'는 신호로 유효하다"고 했으나, <b>신고 시 3필드 리셋 자체가 폐기</b>됐으므로 그 경로는 존재하지
 * 않는다({@code DeidentReportService} 참조). 남는 경로가 하나로 줄었을 뿐 프리필·{@code *Source} 는
 * 그대로 유지한다. 상세 경위는 {@code VideoPrivacyMetaService} 클래스 주석.
 *
 * <h3>★ 프리필 원천 = {@link ExportPrivacyPolicy} 비식별 기본상수 (2026-08-03 정정 — 구 PRVC_TYPE_CD 파생 폐기)</h3>
 * 저장값(수동)이 있으면 필드별로 그 값을 우선하고, 없으면 <b>export 가 실제로 쓰는 비식별 기본상수</b>
 * ({@code DEID_DEFAULT_ANONYMITY} 등 = Y/N/N)를 그대로 반환한다(effective value).
 *
 * <p><b>왜 파생을 폐기했나</b>: 구 프리필은 {@code PRVC_TYPE_CD=='ANONY' ? Y : N} 등으로 영상의
 * 개인정보 유형에서 파생했는데, 선행 Phase 에서 업로드가 {@code PRVC} 고정(fail-closed)이 되면서
 * <b>실질 모든 신규 영상에서 화면이 {@code anonymity=N} 을 보여줬다</b>. 반면 같은 축의 비식별 export
 * {@code image} 블록은 미입력 시 기본상수 {@code Y} 를 실었다 — 패널 문구가 "저장한 값은 비식별
 * 학습데이터에 반영"이라고 안내하는데 <b>사용자가 보는 값과 파일에 실린 값이 어긋났다</b>. 또 영상 패널
 * ({@code VideoPrivacyMetaService}, 기본 Y)과 프레임 패널(기본 N)이 같은 개념에 다른 기본값을 표시했다.
 *
 * <p><b>상수를 복제하지 않는다</b>: 값의 단일 원천은 {@code ExportPrivacyPolicy} 이며 이 서비스는 참조만
 * 한다. 판정 로직·상수를 복제하면 한쪽만 갱신돼 어긋난다 — 이 결함 자체가 그 사례다.
 * 프리필이 더 이상 영상 행을 읽지 않으므로 {@code VideoRepository} 의존과 rawSn 캐시도 함께 제거했다.
 *
 * <p><b>★ 저장값이 export 를 덮는 범위는 {@code DEIDENTIFIED} 산출물뿐이다</b> (2026-08-03 확정 —
 * 구 "{@code ORIGINAL} 한정" 정책의 <b>정확한 반전</b>): 학습데이터 export 의 {@code image} 블록
 * {@code anonymity}/{@code pseudonymity}/{@code privacy_included} 는 {@code DEIDENTIFIED} 에서만
 * <b>수동값 우선</b>(미입력 시 기본상수 Y/N/N)이고, {@code ORIGINAL} 은 <b>3필드 모두 null</b> 이다
 * (원천영상은 비식별 처리 전이라 판정이 성립하지 않으므로 값을 지어내지 않는다).
 * 판정은 {@code ExportPrivacyPolicy} 단일 지점이며 구 정책의 폐기 경위도 그 클래스 주석에 있다.
 * ⚠ 구 정책이 걱정하던 "video/image 모순"은 <b>영상 단위 저장소(V163)</b> 신설로 해소됐다 — 이제
 * {@code video} 블록은 영상 단위 수동값을, {@code image} 블록은 프레임 단위 수동값을 읽으며,
 * 두 값이 달라도 모순이 아니라 입도가 다른 두 사실이다.
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
    private final ReviewApprovalGate approvalGate;
    private final ApplicationEventPublisher eventPublisher;

    /** 프레임 개인정보 메타 조회 — 수동값 우선, 미저장 필드는 비식별 기본상수 프리필. */
    public FramePrivacyMetaResponse get(Long srcSn, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        return toEffective(src);
    }

    /**
     * 단건 저장/수정 — 전체 교체(3필드 함께). null 필드는 수동값 삭제(기본상수 폴백).
     *
     * <p><b>비식별 신고 게이트(412) — 유지, 근거만 교체 (2026-08-04)</b>: 영상 축
     * PUT({@code VideoPrivacyMetaService})과 <b>동일</b>하게 신고 구간({@code DE_IDNTF_YN='F'})에서
     * 차단한다. <b>근거</b>: 신고 구간은 "비식별이 잘못됐다"고 알려진 구간이고, 그 잘못된 비식별본 위에서
     * 내린 개인정보 판정을 이 구간에 새로 쓰면 resolve 후 재산출 때 그 값이 그대로 관제로 나간다.
     * 프레임 축 PUT 이 열려 있으면 영상 축만 막은 것이 <b>비대칭을 없앤 게 아니라 옮긴 것</b>이 되므로
     * 양쪽에 건다. 판정은 단일 원천 {@code DeidentReportGate}
     * ({@code LabelAccessGuard#requireNotUnderDeidentReport} 경유)만 쓴다 — 컨트롤러가 판정을 복제
     * 보유하면 정책 갱신 때 조용히 뒤처진다.
     * ⚠ 구 근거("신고 접수는 프레임 축과 영상 축을 함께 <b>리셋</b>하는데 PUT 이 열려 있으면 같은 우회가
     * 성립한다")는 <b>폐기</b>됐다 — 신고는 더 이상 리셋하지 않는다. <b>게이트 자체는 리셋 여부와
     * 무관하게 성립</b>하므로 제거하지 말 것.
     *
     * <p><b>촬영환경({@code EnvironmentMetaService})은 이 게이트 대상이 아니다</b> — 날씨/시간대/계절은
     * <b>PII 축이 아니라서</b> 신고 구간에 수정해도 "잘못된 비식별본 위의 개인정보 판정"이라는 위 근거가
     * 성립하지 않는다. 대칭을 이유로 확대 적용하지 않는다.
     */
    @Transactional("controlTransactionManager")
    public FramePrivacyMetaResponse update(Long srcSn, FramePrivacyMetaUpdateRequest req, TokenClaims actor) {
        LsDataSrc src = guard.verifyAndGet(srcSn, actor);
        // 인가 통과 후 평가하는 프리컨디션 — 역할 무관(REVIEWER 포함).
        guard.requireNotUnderDeidentReport(src.getRawSn());
        applyAndNotify(src, req.anonymity(), req.pseudonymity(), req.privacyIncluded(), actor);
        return toEffective(src);
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
            // 비식별 신고 게이트도 같은 자리에서 rawSn 당 1회 — 단건 PUT 과 동일 정책(412, 역할 무관).
            // 인가 이후에 평가한다(게이트가 인가를 대체·우회하지 않게 순서 고정).
            if (authorizedRawSns.add(rawSn)) {
                guard.verifyRawAccess(rawSn, actor);
                guard.requireNotUnderDeidentReport(rawSn);
            }
            src.updatePrivacyMeta(item.anonymity(), item.pseudonymity(), item.privacyIncluded());
            toSave.add(src);
            if (approvalGate.isApprovedCached(rawSn, approvedCache)) {
                // HIGH-C(Phase 5C) — 개인정보 메타(pseudonymity/privacyIncluded)는 export JSON 으로 나가므로
                //   승인 후 수정 시 export 폴더를 새 버전으로 전량 재생성해야 데이터마트가 동기화된다.
                //   exportRegenerated=true 로 발행(2026-07-31부터 anonymity 도 수동값이 export 를 덮으므로
                //   3필드 모두 JSON 에 실린다). 구 4-arg=false 는 재생성을 트리거하지 못했다.
                eventPublisher.publishEvent(new TaskModifiedEvent(
                        rawSn, src.getSrcSn(), ChangeType.META_UPDATED, guard.parseUserNo(actor.sub()), true, true));
            }
            result.add(toEffective(src));
        }
        srcRepository.saveAll(toSave);
        log.info("[FramePrivacyMeta] bulk-updated count={} rawSns={}", items.size(), authorizedRawSns.size());
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
        if (approvalGate.isApproved(rawSn)) {
            // HIGH-C(Phase 5C) — single 경로도 bulk 와 동일: 개인정보 메타 수정은 export JSON 을 바꾸므로
            //   exportRegenerated=true 로 발행해 새 버전 폴더로 전량 재생성 후 통지가 나가게 한다.
            // Phase 7a-1 — needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    rawSn, src.getSrcSn(), ChangeType.META_UPDATED, guard.parseUserNo(actor.sub()), true, true));
        }
    }

    /**
     * 저장(수동)값 우선, 미저장 필드는 <b>비식별 기본상수</b>({@link ExportPrivacyPolicy})로 채운 유효값 응답
     * + <b>항목별 출처</b>({@code MANUAL}/{@code DERIVED}) 병기.
     * 상수를 이 클래스에 복제하지 않고 export 판정기의 값을 그대로 참조한다 — 화면 프리필과 산출 파일이
     * 어긋나지 않게 하는 유일한 방법이다(클래스 주석 "왜 파생을 폐기했나" 참조).
     *
     * <p><b>출처를 함께 내리는 이유</b>: 값만 내리면 화면이 프리필({@code DERIVED})을 그대로 PUT 으로
     * 되돌려 보낼 때 <b>기본상수가 사람의 판정으로 승격</b>되는데 서버는 이를 구분할 수 없다.
     * 영상 축({@code VideoPrivacyMetaService#toResponse})과 <b>동일한 판정</b>이며 출처 어휘도
     * {@link VideoPrivacyMetaResponse} 상수를 <b>참조</b>한다(리터럴 재선언 금지).
     */
    private FramePrivacyMetaResponse toEffective(LsDataSrc src) {
        boolean manualAnonymity = isPresent(src.getAnonyInclYn());
        boolean manualPseudonymity = isPresent(src.getPsdoInclYn());
        boolean manualPrivacyIncluded = isPresent(src.getPrvcInclYn());
        String anonymity = firstNonBlank(src.getAnonyInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
        String pseudonymity = firstNonBlank(src.getPsdoInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY);
        String privacyIncluded =
                firstNonBlank(src.getPrvcInclYn(), ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED);
        return new FramePrivacyMetaResponse(src.getSrcSn(), anonymity, pseudonymity, privacyIncluded,
                source(manualAnonymity), source(manualPseudonymity), source(manualPrivacyIncluded));
    }

    /** 수동 저장값 보유 여부 — {@code firstNonBlank} 의 폴백 조건과 <b>같은 술어</b>여야 한다. */
    private static boolean isPresent(String yn) {
        return yn != null && !yn.isBlank();
    }

    /** 출처 표기 — 어휘는 영상 축 상수를 참조한다(두 축이 같은 문자열을 내려야 FE 가 한 벌로 처리한다). */
    private static String source(boolean manual) {
        return manual ? VideoPrivacyMetaResponse.SOURCE_MANUAL : VideoPrivacyMetaResponse.SOURCE_DERIVED;
    }

    private static String firstNonBlank(String primary, String fallback) {
        return (primary == null || primary.isBlank()) ? fallback : primary;
    }
}
