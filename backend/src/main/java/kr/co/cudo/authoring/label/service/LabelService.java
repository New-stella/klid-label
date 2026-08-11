package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.common.util.KeypointPoint;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelHistoryResponse;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Phase 6 — 라벨 CRUD 서비스.
 *
 * 보안 (Critical):
 *  - IDOR (CWE-639): WORKER 는 본인 배정 영상의 프레임에만 라벨 편집 가능 (LabelAccessGuard 위임).
 *  - REVIEWER 는 모든 프레임 접근 가능 (검수 책임).
 *  - 좌표 검증 (CWE-20): 음수 좌표 차단, polygon 최대 1000 점 (CWE-770 DoS 방어).
 *  - Mass Assignment (CWE-915): autoLblYn 은 요청 DTO 에서 무시 (정책: 자동 라벨 수정 시에도 'Y' 유지).
 *    R9 provenance(source/confScore/algorithm)는 신규 삽입 힌트로만 허용하고 값 검증(범위·화이트리스트)을
 *    통과해야 하며, role/isAdmin 같은 민감 필드는 노출하지 않는다(내부 채널 한정 트러스트 경계).
 *
 * 라벨 저장(임시저장)은 LS_DATA_LBL upsert 만 수행한다. 학습데이터 버전 스냅샷(LS_LABEL_VERSION)은
 * 검수 승인(APPROVED) 시점에 VersionService.commitApproved 로 생성한다(SFR-08).
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
public class LabelService {

    /** CWE-770 DoS — 단일 라벨 좌표 점 최대 개수. */
    public static final int MAX_POINTS_PER_LABEL = 1000;

    private final LsDataLblRepository labelRepository;
    private final LsDataLblAiInfoRepository aiInfoRepository;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final LabelAccessGuard accessGuard;
    private final ObjectMapper objectMapper;
    /** Phase 2 — LS_LABEL 마스터 조회 (labelId 검증 + 응답 enrichment). */
    private final LsLabelRepository lsLabelRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 검수 완료(APPROVED) 여부 판정 + 재검토 표시 단일 원천. */
    private final ReviewApprovalGate approvalGate;
    /** Phase 2 — 라벨 변경 이력(ADDED/UPDATED/DELETED) 감사 기록/조회. */
    private final LsDataLblHstryRepository labelHistoryRepository;
    /** Phase 2 full-replace — 삭제 라벨의 속성값(자식) 선삭제(FK 고아 방지). */
    private final LsDataLblAttrValRepository attrValRepository;
    /** C-ISSUE-22 — 좌표 상한(이미지 폭/높이) 검증 기준값 공급(측정 불가 시 상한만 skip). */
    private final FrameBoundsResolver frameBoundsResolver;
    /** 이력 응답의 작성자 표시명(USER_NM) 해석 — 사번→이름 판정 단일 헬퍼(배치 1회). */
    private final UserNameResolver userNameResolver;
    /** R4·R5 — 프레임 폐기·복원 상태 전이 + 감사의 단일 적용 지점(원자 UPDATE·멱등). */
    private final FrameDiscardApplier frameDiscardApplier;

    /** CWE-770 DoS — 라벨 히스토리 조회 페이지 크기 상한. */
    public static final int MAX_HISTORY_PAGE_SIZE = 100;

    public LabelService(LsDataLblRepository labelRepository,
                        LsDataLblAiInfoRepository aiInfoRepository,
                        LsDataSrcRepository srcRepository,
                        VideoRepository videoRepository,
                        WorkLockService workLockService,
                        LabelAccessGuard accessGuard,
                        ObjectMapper objectMapper,
                        LsLabelRepository lsLabelRepository,
                        ApplicationEventPublisher eventPublisher,
                        ReviewApprovalGate approvalGate,
                        LsDataLblHstryRepository labelHistoryRepository,
                        LsDataLblAttrValRepository attrValRepository,
                        FrameBoundsResolver frameBoundsResolver,
                        UserNameResolver userNameResolver,
                        FrameDiscardApplier frameDiscardApplier) {
        this.labelRepository = labelRepository;
        this.aiInfoRepository = aiInfoRepository;
        this.srcRepository = srcRepository;
        this.videoRepository = videoRepository;
        this.workLockService = workLockService;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
        this.lsLabelRepository = lsLabelRepository;
        this.eventPublisher = eventPublisher;
        this.approvalGate = approvalGate;
        this.labelHistoryRepository = labelHistoryRepository;
        this.attrValRepository = attrValRepository;
        this.frameBoundsResolver = frameBoundsResolver;
        this.userNameResolver = userNameResolver;
        this.frameDiscardApplier = frameDiscardApplier;
    }

    /**
     * Phase 6 — 라벨 목록에 대한 LS_DATA_LBL_AI_INFO 일괄 lookup.
     * N+1 회피: 라벨 수만큼 SELECT 가 아니라 IN 절 1회로 결합 응답에 채울 맵을 만든다.
     * 빈 라벨 목록이면 Repository 호출 자체를 skip 한다.
     */
    private Map<Long, LsDataLblAiInfo> resolveAiInfoMap(List<LsDataLbl> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        List<Long> labelSns = labels.stream().map(LsDataLbl::getLblSn).toList();
        return aiInfoRepository.findByDataLblSnIn(labelSns).stream()
                .collect(Collectors.toMap(
                        LsDataLblAiInfo::getDataLblSn,
                        Function.identity(),
                        (a, b) -> a));
    }

    /**
     * Phase 2 — 라벨 목록의 LABEL_ID FK 에 해당하는 LS_LABEL 마스터 일괄 lookup.
     * N+1 회피: 라벨 수만큼 SELECT 가 아니라 IN 절 1회 ({@code findAllById}).
     * 모든 LABEL_ID 가 null 이면 Repository 호출 skip.
     */
    private Map<Long, LsLabel> resolveLsLabelMap(List<LsDataLbl> labels) {
        if (labels == null || labels.isEmpty()) {
            return Map.of();
        }
        Set<Long> labelIds = new HashSet<>();
        for (LsDataLbl e : labels) {
            if (e.getLabelId() != null) {
                labelIds.add(e.getLabelId());
            }
        }
        if (labelIds.isEmpty()) {
            return Map.of();
        }
        return lsLabelRepository.findAllById(labelIds).stream()
                .collect(Collectors.toMap(LsLabel::getLabelId, Function.identity(), (a, b) -> a));
    }

    /**
     * 프레임 라벨 조회 — WORKER 는 본인 배정 프레임만, REVIEWER 는 모두.
     *
     * <p>응답에 영상(rawSn=videoId) 및 동일 영상의 형제 프레임 (siblings) 메타를 함께 반환.
     * N+1 회피: 권한 검사 시점에 LsDataSrc 1회 조회 + siblings 조회 1회 = SELECT 2회.
     */
    public LabelResponse getByFrame(Long srcSn, TokenClaims actor) {
        return getByFrame(srcSn, actor, false);
    }

    /**
     * Phase 3 — 라벨 조회 (REVIEWER 한정 RAW 프레임 옵션 지원).
     *
     * <p>frameImageType 결정 규칙:
     * <ul>
     *   <li>WORKER → 'DEID' (라벨러는 비식별 영상만 본다)</li>
     *   <li>REVIEWER + allowRaw=true → 'RAW' (검수자가 명시적으로 원본 요청)</li>
     *   <li>그 외 → 'DEID'</li>
     * </ul>
     */
    public LabelResponse getByFrame(Long srcSn, TokenClaims actor, boolean allowRaw) {
        LsDataSrc current = accessGuard.verifyAndGet(srcSn, actor);
        // S7 (HIGH — CWE-359) — 비식별 누락 신고 구간(DE_IDNTF_YN='F')에는 라벨 좌표를 내려주지 않는다.
        //   신고가 라벨을 삭제하지 않고 보존하도록 정책이 반전(2026-07-27)되면서, 영상 스트리밍만 막혀 있고
        //   라벨(=PII 위치 특정 정보)은 계속 조회되던 노출창을 닫는다. 인가 검사 <b>이후</b> 평가해 게이트가
        //   인가를 우회·대체하지 않게 하며, resolve('F'→'Y')로 자동 해제되어 보존 라벨을 그대로 재사용한다.
        accessGuard.requireNotUnderDeidentReport(current.getRawSn());
        List<LsDataLbl> labels = labelRepository.findBySrcSn(srcSn);
        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());
        String frameImageType = resolveFrameImageType(actor, allowRaw);
        // Phase 3 보강 — FE 가 라벨링 화면 진입 시 영상 잠금 상태(LOCKED_FOR_REDEIDENT)를 사전 인지하도록 응답에 포함.
        // 잠금된 영상은 라벨 저장 자체가 차단되므로(아래 bulkUpsert 가드 참조) UI 측 비활성화 단서로 사용된다.
        // hotfix: 전체 row fetch 회피 — lockSttsCd 단일 컬럼 projection 사용 (PK 인덱스 lookup).
        // H-ISSUE-41 — 응답 코드값은 FE 판정 정본(LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT)이다.
        //   락 <b>행</b>의 상태값(LsAuthWorkLock.STATUS_LOCKED='LOCKED')은 내부 저장 모델이라 축이 다르다 —
        //   그 값을 그대로 내려보내면 FE 가 잠금을 인지하지 못해 배너·비활성화가 전부 미동작한다.
        String lockSttsCd = workLockService.isRawLocked(current.getRawSn())
                ? LabelResponse.LOCK_STTS_LOCKED_FOR_REDEIDENT
                : null;
        // Phase 6 — autoLblYn/confScore/lblSrcCd 는 LS_DATA_LBL_AI_INFO 에서 채움 (N+1 회피 일괄 lookup)
        Map<Long, LsDataLblAiInfo> aiInfoMap = resolveAiInfoMap(labels);
        // Phase 2 — labelName/color 는 LS_LABEL 에서 채움 (N+1 회피 일괄 lookup)
        Map<Long, LsLabel> lsLabelMap = resolveLsLabelMap(labels);
        // R5 — 형제 프레임별 라벨 존재 여부(hasLabel) — 프레임 strip SAVED(연두) 판정용. 프레임 수와
        // 무관하게 IN 절 1회로 라벨 보유 프레임 집합을 조회한다(N+1 금지).
        Set<Long> labeledSrcSns = resolveLabeledSrcSns(siblings);
        // C-ISSUE-21 — 조회 응답에 현재 라벨셋 버전을 <b>명시</b> 전달한다(FE 가 저장 시 되돌려 보내는 토큰).
        //   DEV_FIX(H12): DTO 가 엔티티에서 몰래 읽지 않게 하고(원자 UPDATE 후 stale 위험), 이 경로에서만
        //   "같은 트랜잭션에서 방금 읽은 값" 임을 근거로 엔티티 값을 쓴다.
        return LabelResponse.of(current, siblings, labels, frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, labeledSrcSns, current.getLabelVersion(), objectMapper, true);
    }

    /**
     * R5 — 형제 프레임 목록 중 라벨이 1건 이상 존재하는 프레임 srcSn 집합.
     * N+1 회피: 프레임마다 COUNT 하지 않고 단일 IN 쿼리({@code findDistinctSrcSnsWithLabelIn}) 1회.
     * 빈 목록이면 Repository 호출 skip.
     */
    private Set<Long> resolveLabeledSrcSns(List<LsDataSrc> siblings) {
        if (siblings == null || siblings.isEmpty()) {
            return Set.of();
        }
        List<Long> srcSns = siblings.stream().map(LsDataSrc::getSrcSn).toList();
        return new HashSet<>(labelRepository.findDistinctSrcSnsWithLabelIn(srcSns));
    }

    /** Phase 3 — actor + raw 요청 여부 → frameImageType 결정 (단일 진실의 원천). */
    public static String resolveFrameImageType(TokenClaims actor, boolean allowRaw) {
        if (actor != null && actor.role() == Role.REVIEWER && allowRaw) {
            return "RAW";
        }
        return "DEID";
    }

    /**
     * 프레임 라벨 bulk upsert — <b>프레임 전체 교체(full-replace)</b>.
     *
     * <p>신규/기존 분기는 {@link #isNewLabel} <b>단일 술어</b>가 결정한다 — 판정 축은 "그 프레임(srcSn)에
     * 실재하는 라벨인가"이며 {@code id==null}·타 프레임 id·미존재 id 는 <b>모두 신규</b>다(C-ISSUE-61/62).
     *  - 신규 : INSERT — source 가 AUTO 계열이면 AUTO_LBL_YN='Y' + LS_DATA_LBL_AI_INFO(신뢰도/알고리즘)
     *           기록(R9 온라인 오토라벨 출처 보존), 그 외(MANUAL/미지정)는 기존대로 수동 저장(AUTO_LBL_YN='N').
     *  - 기존 : UPDATE (AUTO_LBL_YN 유지 — 자동 라벨이라도 'Y' 그대로, provenance 힌트 무시)
     *  - <b>요청에 빠진 기존 라벨은 실제 삭제(full-replace)</b> — FE 는 프레임 전체 라벨 세트를 전송하는 계약이다.
     *    삭제 대상은 현재 프레임(existing=findBySrcSn(srcSn)) 소유 라벨에 한정하며, 자식(ATTR_VAL→AI_INFO)→
     *    부모(LBL) 순으로 bulk 삭제해 FK 고아를 방지한다(HIGH #1/#2).
     *
     * <p>Mass Assignment(CWE-915) 트러스트 경계: provenance(source/confScore/algorithm)는 DTO @Valid 로
     * 범위·화이트리스트 검증을 통과한 값만 반영하며, AUTO_LBL_YN 은 요청이 직접 지정하지 못하고 source 에서
     * 서버가 파생한다(요청은 role/isAdmin 등 민감 필드를 담지 않음 — 내부 채널 REVIEWER/WORKER 한정).
     */
    @Transactional("controlTransactionManager")
    public LabelResponse bulkUpsert(Long srcSn, LabelBulkUpsertRequest req, TokenClaims actor) {
        LsDataSrc current = accessGuard.verifyAndGet(srcSn, actor);
        Long actorNo = accessGuard.parseUserNo(actor.sub());

        // C-ISSUE-22(3차) — 비식별 누락 신고 구간(DE_IDNTF_YN='F')에는 <b>저장도</b> 막는다(412).
        //   구 방어는 작업락 하나뿐이었는데 신고 락은 6h 만료 후 WorkLockSweepJob 이 회수하는 반면
        //   'F' 는 resolve 까지 남는다. 그 창에서 "조회 412 ↔ 저장 200" 비대칭이 열려, 조회가 막힌
        //   작업자/FE 가 불완전(또는 빈) 세트를 보내면 full-replace 계약상 기존 라벨이 전량 삭제됐고
        //   저장 응답에 좌표가 실려 412 열람 차단까지 우회됐다(CWE-359/863, OWASP A10:2025 fail-open).
        //   판정은 DeidentReportGate 단일 원천에 위임한다(호출부마다 "F" 비교를 재구현하지 않는다).
        //   <b>락 검사보다 먼저</b> 평가한다 — 신고 축의 응답을 락 유무와 무관하게 412 로 통일해,
        //   응답 코드(409/412)가 영상 잠금 상태를 알려주는 오라클이 되지 않게 한다. 인가
        //   (verifyAndGet) 이후이므로 이 게이트가 인가를 대체·우회하지 않는다.
        //
        //   <b>무잠금 판정을 쓰는 이유 — 잔여 TOCTOU 창을 의도적으로 남긴다(CWE-367)</b>:
        //   판정은 DeidentReportGate.isUnderDeidentReport(단일 컬럼 SELECT, 무잠금)이므로, 판정 직후
        //   아래 프레임 행 락(lockAndReadLabelVersion) 획득 사이에 신고(DeidentReportService.doReport,
        //   RAW 행 FOR UPDATE + markDeidentified("F"))가 커밋되면 그 저장 <b>1건</b>은 통과한다.
        //   허용 근거: ①이 트랜잭션은 수십 ms 짜리 단일 저장이라(수 분 걸리는 export 와 다르다) 창이
        //   극히 짧고 ②신고는 라벨을 삭제하지 않고 보존하므로(2026-07-27 확정) 그 창에 저장된 라벨도
        //   유실이 아니라 resolve 후 그대로 재사용되며 ③신고 커밋 순간부터 조회·이력·프레임 이미지·
        //   export 가 모두 412/보류로 닫혀 PII 좌표가 경계 밖으로 나가지 않는다.
        //   잠금 변형(DeidentReportGate.isUnderDeidentReportLocked, RAW 행 FOR UPDATE)을 쓰면 창은
        //   닫히지만 <b>모든 라벨 저장이 영상 단위 배타락</b>을 잡아, 같은 영상의 서로 다른 프레임을
        //   작업하는 작업자끼리 직렬화된다(현재는 프레임 단위 직렬화). 장시간 산출인
        //   DatasetExportTxService 가 그 비용을 감수하는 것과 달리 여기서는 이득 대비 비용이 맞지 않아
        //   채택하지 않는다(과설계 방지 — 잔여 위험을 인지·수용한 선택이다).
        accessGuard.requireNotUnderDeidentReport(current.getRawSn());

        // Phase 3 — 비식별 재처리 중 영상은 라벨 수정 금지 (Race Condition 방어 + 정책)
        //   신고와 무관한 락(트랙 병합·재비식별 진행 중)은 <b>일시적 충돌</b>이므로 기존대로 409 유지.
        if (workLockService.isRawLocked(current.getRawSn())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다.");
        }

        FrameSaveOutcome outcome = applyFrameSave(srcSn, current, req, actorNo, FrameSaveOptions.NONE);

        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());
        String frameImageType = resolveFrameImageType(actor, false);
        // Phase 3 보강 — bulkUpsert 통과 시점에는 잠금이 없음이 보장되지만(위 가드)
        // 응답 스키마 일관성을 위해 동일 필드를 반환한다. raw 는 이미 fetch 됨 → 추가 쿼리 없음.
        String lockSttsCd = null;

        // Phase 6 — bulkUpsert 결과에 자동 라벨(수정만 이루어진)이 섞일 수 있으므로 AI Info lookup.
        // 수동 신규 라벨은 row 없음 → 자연스럽게 autoLblYn='N' 응답.
        Map<Long, LsDataLblAiInfo> aiInfoMap = resolveAiInfoMap(outcome.labels());
        // Phase 2 — 응답 labelName/color enrichment.
        Map<Long, LsLabel> lsLabelMap = resolveLsLabelMap(outcome.labels());
        // R5 — 저장 직후 응답에도 형제 프레임 hasLabel 반영(방금 저장한 프레임 포함). IN 절 1회(N+1 금지).
        Set<Long> labeledSrcSns = resolveLabeledSrcSns(siblings);

        // 라벨 저장(임시저장)은 LS_DATA_LBL upsert + 작업본 갱신만 수행한다.
        // 학습데이터 버전 스냅샷(LS_LABEL_VERSION)은 검수 승인(APPROVED) 시점에만 생성한다(SFR-08).
        // → 저장 시 versionService 자동 커밋을 호출하지 않는다.

        return LabelResponse.of(current, siblings, outcome.labels(), frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, labeledSrcSns, outcome.labelVersion(), objectMapper, true);
    }

    /**
     * 프레임 1건 저장의 결과 — 영상 단위 확정 저장(API-196)이 프레임별 결과를 모으는 축이다.
     *
     * @param labels         저장 후 그 프레임에 남은 라벨 엔티티(응답 enrichment 입력)
     * @param labelVersion   저장 후 라벨셋 판번호(무변경이면 기존 값 그대로)
     * @param discardOutcome 폐기·복원 전이 결과
     * @param labelsChanged  라벨 본문이 실제로 바뀌었는지(무변경 재저장 판정)
     */
    record FrameSaveOutcome(List<LsDataLbl> labels, long labelVersion,
                            FrameDiscardApplier.Outcome discardOutcome, boolean labelsChanged) {
    }

    /**
     * 저장 코어의 선택 입력 — 호출부가 <b>미리 확보한</b> 값을 넘길 수 있게 한다.
     *
     * <h3>{@code preResolvedBounds} — 트랜잭션 안에서 이미지를 디코딩하지 않기 위한 축 (Critical)</h3>
     * 좌표 상한 기준값은 {@link FrameBoundsResolver} 가 <b>프레임 이미지 파일을 열어 디코딩</b>해 얻는다
     * (해상도 컬럼이 DB 에 없다). 프레임 단위 저장은 그 비용을 1회 치르지만, 영상 단위 확정 저장은
     * 프레임 수만큼 반복하면서 <b>프레임 행 락과 DB 커넥션을 쥔 채</b> NAS I/O 를 한다 — 캐시는 프로세스
     * 로컬이라 2노드 콜드 스타트에서 전량 미스가 정상 시나리오다. 이 저장소에는 정확히 같은 이유로 프레임
     * 이미지 서빙의 파일 I/O 를 트랜잭션 밖으로 뺀 전례가 있다({@code FrameImageServingHardeningTest}).
     *
     * <p>그래서 영상 단위 경로는 <b>트랜잭션 시작 전에</b> 전 프레임의 기준값을 확보해 여기로 넘기고,
     * 코어는 해석기를 호출하지 않는다. {@code null} 이면(프레임 단위 경로) 종전대로 코어가 해석한다.
     *
     * @param preResolvedBounds 미리 확보한 {@code [width, height]}. {@code null} 이면 코어가 해석한다
     *                          (측정 불가로 확보하지 못한 프레임도 {@code null} 이며, 그때는 상한 검증만
     *                          건너뛰는 기존 fail-open 정책을 그대로 따른다)
     * @param boundsResolved    기준값 확보를 <b>이미 시도했는지</b>. {@code true} 면 코어가 해석기를
     *                          호출하지 않는다 — {@code preResolvedBounds == null} 을 "미시도"와 "측정
     *                          불가"로 구분하지 못하면, 측정 불가 프레임마다 코어가 다시 파일을 열어
     *                          트랜잭션 밖으로 뺀 의미가 사라진다
     */
    record FrameSaveOptions(int[] preResolvedBounds, boolean boundsResolved) {

        /** 프레임 단위 저장 — 코어가 기준값을 직접 해석한다(종전 동작). */
        static final FrameSaveOptions NONE = new FrameSaveOptions(null, false);

        /** 영상 단위 저장 — 트랜잭션 밖에서 확보한 기준값을 그대로 쓴다(측정 불가면 null). */
        static FrameSaveOptions withBounds(int[] bounds) {
            return new FrameSaveOptions(bounds, true);
        }
    }

    /**
     * 프레임 라벨 full-replace 의 <b>저장 코어</b> — 게이트 통과 이후의 모든 쓰기.
     *
     * <h3>왜 분리하는가 (Critical)</h3>
     * 영상 단위 확정 저장(API-196 {@code PUT /v1/videos/{rawSn}/labels})이 프레임마다 <b>같은 규칙</b>
     * 으로 저장해야 한다. 그쪽에서 이 로직을 다시 구현하면 낙관적 동시성(CAS)·좌표 검증·이력·통지·
     * 폐기 전이가 두 곳으로 갈려 한쪽만 갱신되는 순간 어긋난다(이 저장소의 반복 결함 패턴).
     * 따라서 <b>게이트(인가·신고 412·작업락 409)는 호출부가</b>, <b>쓰기는 이 메서드가</b> 소유한다.
     *
     * <p>스코프별 게이트가 다른 것이 분리 기준이다: 프레임 축은 {@code verifyAndGet(srcSn)} 으로
     * 프레임 소유를 검증하고, 영상 축은 {@code verifyRawAccess(rawSn)} 한 번으로 검증한 뒤 요청
     * 프레임이 <b>그 영상 소속인지</b>를 대조한다(IDOR — CWE-639).
     *
     * <p>자체 {@code @Transactional} 을 두지 않는다 — 호출부의 트랜잭션에 그대로 합류해야 영상 단위
     * 저장이 <b>한 트랜잭션</b>이 된다(중간 프레임 실패 시 전량 롤백). 자기호출 프록시 함정과도 무관하다.
     *
     * @param srcSn   대상 프레임 PK — <b>호출부가 인가를 통과시킨 그 식별자</b>를 그대로 받는다.
     *                엔티티에서 다시 꺼내지 않는 이유는 인가·행 락·상태 변경이 모두 <b>같은 식별자</b>를
     *                대상으로 했음이 시그니처에서 드러나야 하기 때문이다({@link FrameDiscardApplier#apply}
     *                와 같은 규약 — 엔티티 식별자 적재 여부에 의존하지 않는다).
     * @param current 그 프레임 엔티티(호출부가 인가 검사로 이미 확보)
     */
    FrameSaveOutcome applyFrameSave(Long srcSn, LsDataSrc current, LabelBulkUpsertRequest req,
                                    Long actorNo, FrameSaveOptions options) {
        // LOW hardening — 동일 id 가 items 에 중복되면 last-value-wins 로 dedup 한다(같은 라벨 이중 처리·
        // 카운트 중복 방지). id==null(신규)은 모두 유지, non-null id 는 마지막 항목만 유효(그 값이 최종 저장값).
        List<LabelItemDto> items = dedupById(req.items());

        // C-ISSUE-21 — 프레임 행 비관적 락으로 동시 full-replace 를 <b>직렬화</b>한다. 이 락을 잡은 뒤에
        //   existing 을 읽으므로, 경쟁 트랜잭션은 앞 트랜잭션이 커밋한 뒤에야 existing 을 관측한다
        //   (2노드 Active-Active 라 JVM 락은 방어가 되지 않아 DB 락으로만 해결).
        // DEV_FIX(H2③) — CAS 기준값은 반드시 <b>락 획득 시점의 DB 값</b>이어야 한다. 엔티티 조회
        //   (findBySrcSnForUpdate)는 진입부 accessGuard 가 이미 적재한 1차 캐시 인스턴스를 그대로 돌려주고
        //   쿼리 결과로 필드를 덮어쓰지 않으므로, 락을 기다리는 동안 경쟁 트랜잭션이 커밋한 새 버전을
        //   관측하지 못해(=락 획득 前 값) stale 요청이 그대로 통과했다. 스칼라 프로젝션 네이티브 쿼리
        //   (lockAndReadLabelVersion)는 1차 캐시를 우회해 항상 DB 현재 값을 반환한다(락 획득과 동일 문장).
        long baseVersion = srcRepository.lockAndReadLabelVersion(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        requireLabelVersionMatch(srcSn, req.labelVersion(), baseVersion);

        // R4·R5 — 프레임 폐기·복원. 화면에서 한 일은 저장을 눌러야 확정되므로(D8) 전용 엔드포인트가
        //   아니라 이 저장 계약의 선택 필드로 들어온다. 보내지 않으면 현재 값을 그대로 둔다(하위호환).
        //   <b>여기(프레임 행 락 획득 직후)에서 적용하는 이유</b>: 폐기 여부는 라벨셋과 함께 "이 프레임의
        //   확정 상태"를 이루므로 같은 락 구간 안에서 바뀌어야 두 축이 갈라지지 않는다. 새 락을 잡지
        //   않으므로 기존 잠금 순서(프레임 행 → 라벨 행)에 간선을 추가하지 않는다.
        //   인가(verifyAndGet) · 신고 게이트(412) · 작업락(409)을 <b>모두 통과한 뒤</b>라, 폐기가 라벨
        //   저장보다 느슨한 조건으로 들어오는 우회 경로가 없다.
        FrameDiscardApplier.Outcome discardOutcome =
                frameDiscardApplier.apply(srcSn, current, req.dscdYn(), actorNo);

        // C-ISSUE-22 — 좌표 상한(이미지 폭/높이) 기준값. 측정 불가면 null → 상한 검증만 skip(하한·형식은 유지).
        //   기준값은 프레임 이미지 파일에서 실측하므로 라벨셋 버전과 무관하다(current 로 충분).
        //   ★ 영상 단위 확정 저장은 이 값을 <b>트랜잭션 밖에서</b> 미리 확보해 넘긴다 — 여기서 해석하면
        //     프레임 행 락과 커넥션을 쥔 채 프레임마다 이미지를 디코딩한다(FrameSaveOptions 주석).
        int[] bounds = options.boundsResolved()
                ? options.preResolvedBounds()
                : frameBoundsResolver.resolve(current).orElse(null);

        // 기존 라벨 인덱싱 (id 기반 수정용).
        // C-ISSUE-61/62 — <b>사전 검증보다 먼저</b> 읽는다. 검증의 "신규 여부" 판정이 저장 분기와 같은
        //   축(idIndex 실재 여부)을 써야 하기 때문이다(아래 isNewLabel 참조). 프레임 행 락을 이미 잡은
        //   뒤이므로 이 시점의 existing 은 경쟁 트랜잭션 커밋 이후 값이 보장된다(C-ISSUE-21 불변).
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        Map<Long, LsDataLbl> idIndex = new HashMap<>();
        for (LsDataLbl e : existing) {
            idIndex.put(e.getLblSn(), e);
        }

        // 좌표 사전 검증 (트랜잭션 내부에서 한꺼번에 실패해도 롤백 — 여기선 명시적으로 미리 차단)
        for (LabelItemDto item : items) {
            // C-ISSUE-61/62 — 신규 판정은 "그 프레임에 실재하지 않는 라벨"이다. 구 판정(item.id()==null)은
            //   저장 분기(idIndex.containsKey)와 어긋나, 아무 id 나 붙이면 아래 두 상한이 통째로 우회됐다
            //   (미존재 id 는 신규 라벨로 생성되는데 검증만 '기존 수정'으로 오분류 — CWE-20/CWE-1287).
            //   USE_YN 축(isNewLabelAssignment)은 이미 같은 축으로 우회를 막고 있었다.
            boolean isNew = isNewLabel(item, idIndex);
            // 신규 라벨은 점 개수 상한을 강제(CWE-770 DoS 방어). 수동 드로잉/정상 SAM2 결과는
            // 모두 상한 이하이며, SAM2 분할/추적 서비스가 적재 전 simplify 하므로 1000점 초과 신규 입력은 비정상.
            // 기존 라벨은 상한 초과여도 저장 직전 simplify 로 보존한다(ISSUE-1, 아래 capPoints).
            // SKELETON 은 삼중값(17점·v∈{0,1,2}) 전용 검증으로 type-route (기존 2-튜플 경로 불변).
            validatePoints(item.lblTypeCd(), item.points(), isNew);
            // C-ISSUE-22 — 이미지 경계 상한. 신규 라벨은 즉시 강제, 기존 라벨은 좌표가 <b>실제로 바뀔 때만</b>
            //   아래 UPDATE 분기에서 강제한다(이미 경계를 벗어나 저장된 레거시 라벨이 프레임 전체 저장을
            //   영구 차단하는 회귀 방지 — MAX_POINTS 와 동일 정책).
            if (isNew) {
                validateWithinBounds(item.lblTypeCd(), item.points(), bounds);
            }
        }

        // Phase 2 — labelId 사전 검증 (입력에 포함된 모든 labelId 의 존재 확인 + <b>신규 부여</b>에 한한 USE_YN='Y').
        // N+1 회피: distinct labelId 1회 lookup. (응답 enrichment 는 저장 후 result 기준으로 다시 lookup 한다.)
        validateAndLoadLabels(items, idIndex);

        // full-replace 델타 기준 — 요청에 담긴 non-null id(=생존 대상). 여기에 없는 existing 라벨은 삭제된다.
        Set<Long> reqIds = new HashSet<>();
        for (LabelItemDto item : items) {
            if (item.id() != null) {
                reqIds.add(item.id());
            }
        }

        List<LsDataLbl> result = new ArrayList<>();
        // V114 — 라벨 변경 이력. 저장 판정과 동일 술어({@link #isNewLabel})로 종류를 결정하고,
        // 검증·저장·삭제가 모두 통과한 뒤 동일 트랜잭션에서 저장 이벤트 1건으로 원자 기록한다(HIGH #1).
        List<LabelChange> changes = new ArrayList<>();
        String actorId = String.valueOf(actorNo);
        for (LabelItemDto item : items) {
            // ISSUE-1: 저장 직전 점 개수 상한 적용 (SAM2 적재 폴리곤 등 1000점 초과도 simplify 후 저장).
            // SKELETON 은 KeypointSerializer 삼중값 경로로 직렬화 (기존 2-튜플 toJson 경로 불변).
            String pointsJson = serializePoints(item.lblTypeCd(), item.points());
            // C-ISSUE-61/62 — 사전 검증과 <b>같은 술어</b>(isNewLabel)로 분기한다. 두 곳이 각자 조건을
            //   쓰면 어긋난 순간 검증이 비는 창이 열린다(구 결함이 정확히 그 형태였다).
            if (!isNewLabel(item, idIndex)) {
                // idIndex 는 findBySrcSn(srcSn) 로만 채워지므로(현재 프레임 라벨) 이 분기의 라벨은 항상 srcSn 소유
                // → found.getSrcSn().equals(srcSn) 는 언제나 참이라, 과거의 "다른 프레임 라벨이면 FORBIDDEN"
                //   방어는 도달 불가한 죽은 코드였다(DEV_FIX 로 제거). IDOR 관점에서도 무위험:
                //   진입부 accessGuard.verifyAndGet(srcSn) 로 현재 프레임 소유가 검증되고, 타 프레임/미존재 id 는
                //   여기 진입하지 못한 채 else 로 흘러 '현재 프레임 신규 라벨(ADDED)'로 안전 처리된다(타 프레임 라벨 불변).
                LsDataLbl found = idIndex.get(item.id());
                // C-ISSUE-22 — 기존 라벨은 좌표를 <b>실제로 변경</b>할 때만 이미지 경계 상한을 강제한다.
                //   (무변경 재전송/타 필드만 수정은 통과 — 레거시 out-of-bounds 데이터로 프레임 저장이
                //    영구 차단되는 회귀 방지. 반대로 경계 밖으로 <b>옮기는</b> 시도는 여기서 400 으로 막힌다.)
                if (!pointsEqual(found.getPointCn(), pointsJson)) {
                    validateWithinBounds(item.lblTypeCd(), item.points(), bounds);
                }
                // HIGH #3 — before 스냅샷은 반드시 updateUserContent 호출 前에 캡처한다(변경 전 값 보존).
                LabelSnapshot before = snapshotOf(found);
                // Phase 2 — labelId 가 null 이면 기존 값 유지, non-null 이면 검증 후 변경.
                found.updateUserContent(item.lblTypeCd(), item.labelId(), item.label(), pointsJson);
                result.add(found);
                LabelSnapshot after = snapshotOf(found);
                // R7 — 무변경 재저장 노이즈 차단: FE 계약이 '매 저장마다 프레임 전체 세트 전송'이라 실제로 바뀌지
                //   않은 라벨도 UPDATE 분기로 들어온다. before/after 가 실질적으로 동일하면 UPDATED 이력을 남기지
                //   않는다(같으면 mdfcnCnt 미증가 + 이력·통지 미발행). pointCn 은 재직렬화로 표현만 달라질 수
                //   있어(5 vs 5.0) 수치 정규화 비교한다(snapshotsEqual 참조).
                if (!snapshotsEqual(before, after)) {
                    changes.add(LabelChange.updated(found.getLblSn(), found.getLabelNm(), before, after));
                }
            } else {
                LsDataLbl created;
                if (isAutoSource(item.source())) {
                    // R9 — 온라인 오토라벨(AI 탐지/추적) 신규 삽입: AUTO_LBL_YN='Y' 로 저장하고
                    // LS_DATA_LBL_AI_INFO 에 신뢰도·알고리즘을 기록해 출처를 보존한다(수동 둔갑·신뢰도 유실 방지).
                    BigDecimal conf = toScore(item.confScore());
                    created = labelRepository.save(buildAutoLabel(srcSn, item, pointsJson, conf));
                    aiInfoRepository.save(LsDataLblAiInfo.create(
                            created.getLblSn(), current.getRawSn(), srcSn,
                            resolveAiSource(item), conf, actorId));
                } else {
                    created = labelRepository.save(
                            LsDataLbl.createManual(srcSn, item.lblTypeCd(), item.labelId(),
                                    item.label(), pointsJson, actorNo));
                }
                result.add(created);
                changes.add(LabelChange.added(created.getLblSn(), created.getLabelNm(), snapshotOf(created)));
            }
        }

        // full-replace 삭제 델타 — existing 중 요청(reqIds)에 없는 라벨이 삭제 대상.
        // HIGH #2 — 삭제 대상은 반드시 existing(=findBySrcSn(srcSn), 현재 프레임 소유)에 한정되므로
        //   타 프레임/타 영상 라벨은 절대 삭제되지 않는다(진입부 accessGuard 로 프레임 소유도 검증됨).
        // HIGH #4 — delta 는 동일 트랜잭션 내에서 읽은 existing 기준으로 계산해 TOCTOU 창을 최소화한다.
        //   프레임은 단일 WORKER 배정(accessGuard 소유 검증)이라 서로 다른 사용자의 동시 저장은 구조적으로
        //   제한되며, 사후 추적은 아래 deleted 카운트 감사 로깅으로 남긴다(무거운 비관적 락/@Version 미도입).
        List<LsDataLbl> toDelete = existing.stream()
                .filter(e -> !reqIds.contains(e.getLblSn()))
                .toList();
        if (!toDelete.isEmpty()) {
            List<Long> delSns = toDelete.stream().map(LsDataLbl::getLblSn).toList();
            for (LsDataLbl d : toDelete) {
                // HIGH #5/#6 — 삭제 前 before 스냅샷을 남겨 사후 복구 근거를 보존한다(after=null).
                // 주의(의도): before 스냅샷은 LS_DATA_LBL 본문(타입/labelId/label명/좌표)만 담고 속성값
                //   (LS_DATA_LBL_ATTR_VAL)은 포함하지 않는다. 삭제 감사·요약 용도이며, 라벨의 최종 복원 안전망은
                //   검수 완료 시점 스냅샷(LS_LABEL_VERSION, 속성 포함 전체 JSON)이다.
                changes.add(LabelChange.deleted(d.getLblSn(), d.getLabelNm(), snapshotOf(d)));
            }
            // HIGH #1 — FK 고아 방지 순서: 자식(ATTR_VAL) → 자식(AI_INFO) → 부모(LBL).
            //   ATTR_VAL 은 실 FK(FK_LS_DATA_LBL_ATTR_LBL)라 먼저 지우지 않으면 부모 삭제가 FK 위반 500.
            //   모두 bulk delete(N+1 회피 — DeidentReportService.deleteAllVideoLabels 와 동일 순서).
            attrValRepository.deleteByLblSnIn(delSns);
            aiInfoRepository.deleteByDataLblSnIn(delSns);
            labelRepository.deleteAllByIdInBatch(delSns);
        }

        // 감사 이력은 라벨 저장과 원자성이 필요하므로 @Async/AFTER_COMMIT 분리 없이 동일 트랜잭션 내 저장 이벤트 1건 기록.
        // 무변경(changes 비면) 이벤트는 만들지 않는다(R7, LOW #12).
        if (!changes.isEmpty()) {
            labelHistoryRepository.save(LsDataLblHstry.recordSaveEvent(srcSn, actorId, changes));
        }
        // C-ISSUE-21 — 라벨셋이 <b>실제로 바뀐 경우에만</b> 버전을 +1 한다(무변경 재저장은 다른 세션의
        //   보유 버전을 무효화하지 않는다). 프레임 행 락을 쥔 상태라 새 값은 결정적으로 baseVersion+1 이다.
        long newVersion = baseVersion;
        if (!changes.isEmpty()) {
            srcRepository.bumpLabelVersionIn(List.of(srcSn));
            newVersion = baseVersion + 1;
        }
        // HIGH #2 — full-replace 대량 삭제 감사 로깅(민감정보 없이 카운트만 — PII/토큰 미출력).
        log.info("[Label] bulkUpsert srcSn={} actor={} existing={} saved={} deleted={} labelVersion={}->{}",
                srcSn, actorNo, existing.size(), result.size(), toDelete.size(), baseVersion, newVersion);
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전(PENDING/ASSIGNED/IN_REVIEW/PROCESSING 등) 저장은 일반 작업이므로 통지 미발행.
        // LOW #12 — 무변경(changes 비면) 이면 통지도 미발행.
        // R4·R5 — 폐기·복원은 라벨 변경이 없어도 산출물 구성을 바꾸므로 <b>독자적으로</b> 통지 대상이다
        //   (라벨 무변경 + 폐기만 있는 저장이 통지 없이 지나가면 관제가 사라진 프레임을 영영 모른다).
        if ((!changes.isEmpty() || discardOutcome.isChanged())
                && approvalGate.isApproved(current.getRawSn())) {
            // D-ISSUE-44 — bulkUpsert 는 추가/수정/삭제를 한 배치에서 처리하지만, 이번 저장에 실제로
            // 포함된 종류만 발행한다. 구 구현은 전부 LABEL_UPDATED 하나로 뭉개 LABEL_ADDED 가 계약에만
            // 존재하고 어디서도 발행되지 않는 dead 값이었다. 디바운서가 (srcSn ↔ 변경종류) 페어로
            // 축적하므로 같은 프레임에 대해 여러 종류를 발행해도 통지 1건으로 합쳐진다.
            // C-1/C-4(Phase 5C) — 승인 후 라벨 수정은 export 를 새 버전 폴더로 전량 재생성한다.
            //   exportRegenerated=true 로 발행하면 디바운스 flush 가 export(force=true) 를 먼저 마친 뒤
            //   통지(전 프레임 changed_items)를 내보내, 관제가 픽업하는 뷰 출력 OUTPUT_PATH_NM 이 항상 최신 버전이다.
            //   (요구: "데이터마트 학습데이터셋의 라벨링 정보 동기화")
            // Phase 7a-1 — needsRecheck=true (사람이 콘텐츠를 고치는 경로): 재검토 표시만 세운다.
            //   R4·R5 폐기·복원도 사람이 산출물 구성을 고치는 경로라 같은 축이며(D6), 승인 영상에서도
            //   폐기할 수 있게 하되 재검토 표시를 세워 <b>재승인 시점에</b> 관제로 나가게 한다.
            //   exportRegenerated=true — 폐기된 프레임의 이미지 2벌·JSON 이 빠진 새 버전 폴더를 만들어야
            //   관제가 픽업하는 산출물이 실제 구성과 일치한다.
            for (String changeType : toChangeTypes(changes, discardOutcome)) {
                eventPublisher.publishEvent(new TaskModifiedEvent(
                        current.getRawSn(), srcSn, changeType, actorNo, true, true));
            }
        }

        return new FrameSaveOutcome(result, newVersion, discardOutcome, !changes.isEmpty());
    }

    /**
     * C-ISSUE-21 — 요청이 첨부한 라벨셋 버전과 현재 버전을 대조한다(<b>선택 필드</b>).
     *
     * <p>미첨부(null)면 검사를 건너뛴다 — FE 미반영 구간의 기존 저장 플로우가 끊기면 안 되기 때문이다
     * (하위호환). 첨부했는데 다르면 내 화면이 낡았다는 뜻이므로 409 로 거부한다: full-replace 계약이라
     * 낡은 세트를 그대로 저장하면 그사이 다른 세션이 추가한 라벨이 조용히 삭제된다.
     */
    private void requireLabelVersionMatch(Long srcSn, Long requested, long current) {
        if (requested == null || requested == current) {
            return;
        }
        log.warn("[Label] stale label version rejected srcSn={} requested={} current={}",
                srcSn, requested, current);
        throw new CustomException(ErrorCode.CONFLICT,
                "다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요.");
    }

    /**
     * Phase 2 — 프레임 단위 라벨 변경 이력 조회 (최신순 페이징).
     * <ul>
     *   <li>IDOR(CWE-639): 진입 시 {@link LabelAccessGuard#verifyAndGet} 재사용 — WORKER 는 본인 배정 프레임만.</li>
     *   <li>CWE-770: 페이지 크기를 {@link #MAX_HISTORY_PAGE_SIZE} 로 클램프.</li>
     *   <li>정렬: 요청 sort + LBL_HSTRY_SN DESC tiebreaker(동시각 순서 보정, MED #10).</li>
     *   <li>N+1 회피: 페이지 라벨명 enrichment 는 생존 라벨만 IN 1회 lookup(삭제 이력은 label=null).</li>
     * </ul>
     */
    public Page<LabelHistoryResponse> getHistory(Long srcSn, TokenClaims actor, Pageable pageable) {
        LsDataSrc current = accessGuard.verifyAndGet(srcSn, actor);
        // S7 (DEV_FIX-A/H4 — HIGH, CWE-359) — 이력 응답(LabelHistoryResponse.chgDtlCn)에는 before/after
        //   좌표 전문이 실린다. 신고 구간(DE_IDNTF_YN='F')에 라벨 조회(getByFrame)만 막고 이력을 열어두면
        //   같은 좌표를 이력으로 그대로 읽을 수 있어 게이트가 무의미해진다. 동일 단일 게이트를 인가 이후
        //   rawSn 단위 1회 평가하고, resolve('F'→'Y') 로 자동 해제한다.
        accessGuard.requireNotUnderDeidentReport(current.getRawSn());
        Pageable effective = cappedWithTiebreaker(pageable);
        // V114 — 저장 이벤트 행을 그대로 매핑(라벨명 enrichment 는 diff 페이로드로 이관 — Phase 2).
        Page<LsDataLblHstry> page = labelHistoryRepository.findBySrcSn(srcSn, effective);
        // 작성자 표시명(USER_NM) enrichment — 페이지의 사번을 모아 사용자 마스터 IN 조회 1회(N+1 금지).
        //   사번(actor)은 원값 그대로 유지하고 이름만 덧붙인다(하위호환 — FE 폴백 원값).
        UserNameResolver.UserNames names = userNameResolver.resolveAll(
                page.getContent().stream().map(LsDataLblHstry::getRegId).toList());
        return page.map(h -> LabelHistoryResponse.from(h, names.nameOf(h.getRegId())));
    }

    /** 라벨 본문 → diff 스냅샷(before/after 값객체) 변환. */
    private LabelSnapshot snapshotOf(LsDataLbl l) {
        return new LabelSnapshot(l.getLblTypeCd(), l.getLabelId(), l.getLabelNm(), l.getPointCn());
    }

    /**
     * LOW hardening — items 의 중복 {@code id} 를 last-value-wins 로 제거한다.
     * <ul>
     *   <li>id==null(신규 라벨)은 서로 구분 불가하므로 전부 유지한다.</li>
     *   <li>동일 non-null id 가 여러 번 오면 <b>마지막 항목만</b> 유효(그 값이 최종 저장값). 원래 순서는 보존.</li>
     * </ul>
     * 중복이 없으면 입력과 동등한 리스트를 반환한다(오버헤드 최소).
     */
    private List<LabelItemDto> dedupById(List<LabelItemDto> src) {
        Map<Long, Integer> lastIndex = new HashMap<>();
        for (int i = 0; i < src.size(); i++) {
            Long id = src.get(i).id();
            if (id != null) {
                lastIndex.put(id, i);
            }
        }
        List<LabelItemDto> out = new ArrayList<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            LabelItemDto it = src.get(i);
            Long id = it.id();
            // 신규(id==null)이거나, 해당 id 의 마지막 등장 위치면 채택.
            if (id == null || lastIndex.get(id) == i) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * R7 — 두 라벨 스냅샷이 <b>실질적으로 동일</b>한지 판정(무변경 재저장 노이즈 차단).
     * lblTypeCd/labelId/labelNm 은 값 동등, pointCn 은 표현차(부동소수 5 vs 5.0, int/double)를 흡수하기
     * 위해 {@link #pointsEqual} 로 수치 정규화 비교한다.
     */
    private boolean snapshotsEqual(LabelSnapshot before, LabelSnapshot after) {
        return java.util.Objects.equals(before.lblTypeCd(), after.lblTypeCd())
                && java.util.Objects.equals(before.labelId(), after.labelId())
                && java.util.Objects.equals(before.labelNm(), after.labelNm())
                && pointsEqual(before.pointCn(), after.pointCn());
    }

    /**
     * 좌표 JSON 두 개를 <b>수치 정규화</b> 후 비교한다. 문자열이 같으면 fast-path true.
     * <p>재직렬화(예: 요청 {@code 5} → 저장 {@code 5.0})나 정수/실수 표현차만 다른 경우를 '무변경'으로
     * 판정하기 위해, 양쪽을 동일 기준의 {@code List<List<Double>>} 로 정규화해 값 비교한다.
     * <p>DEV_FIX(R7 레거시 포맷 흡수): 저장 포맷은 3종을 유효로 인정한다 —
     * 정규 {@code [[x,y],...]} · 객체배열 {@code [{"x":,"y":},...]} · 평탄 {@code [x1,y1,x2,y2,...]}
     * (Phase 1 정규화 이전 brownfield 데이터). before 가 레거시 포맷이면 예전 {@code List<List<Double>>}
     * 단일 파싱은 예외→오탐(변경됨)이 되어 무변경 재저장이 UPDATED 이력/TASK_MODIFIED 를 유발했다.
     * 이를 막기 위해 {@link LabelPointSerializer#fromJson}(3포맷 흡수)으로 양쪽을 정규 표현으로 통일해 비교한다.
     * <p>SKELETON(삼중값 {@code [[x,y,v],...]})은 {@code fromJson}(2-튜플 파서)이 거부하므로 raw 숫자배열
     * 파싱으로 폴백한다 — SKELETON 은 {@link KeypointSerializer} 결정적 직렬화라 대부분 fast-path 로 처리되며,
     * 폴백은 기존 {@code List<List<Double>>} 비교와 동치라 회귀가 없다.
     * <p>세 경로 모두 실패하는 <b>진짜 손상값</b>만 fail-safe 로 '다름'(false) 처리해 이력을 남긴다.
     */
    private boolean pointsEqual(String a, String b) {
        if (java.util.Objects.equals(a, b)) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        List<List<Double>> na = normalizePoints(a);
        List<List<Double>> nb = normalizePoints(b);
        if (na == null || nb == null) {
            // 정규화 실패(손상 JSON)는 안전하게 '변경됨'으로 간주 — 이력 유실 방지 fail-safe.
            return false;
        }
        return na.equals(nb);
    }

    /**
     * 좌표 문자열을 정규 {@code List<List<Double>>} 표현으로 파싱한다(비교 정규화 전용).
     * <p>① {@link LabelPointSerializer#fromJson}(정규/객체배열/평탄 3포맷) → ② SKELETON 삼중값 등
     * 2-튜플 파서로 못 읽는 포맷은 raw {@code List<List<Double>>} 파싱으로 폴백. 둘 다 실패면 손상값(null).
     */
    private List<List<Double>> normalizePoints(String json) {
        try {
            List<Point> points = LabelPointSerializer.fromJson(json, objectMapper);
            List<List<Double>> out = new ArrayList<>(points.size());
            for (Point p : points) {
                out.add(List.of(p.x(), p.y()));
            }
            return out;
        } catch (Exception ignore) {
            // 2-튜플 파서 거부(예: SKELETON 삼중값) → raw 숫자배열로 폴백.
        }
        try {
            var typeRef = new com.fasterxml.jackson.core.type.TypeReference<List<List<Double>>>() {};
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 페이지 크기 상한 클램프 + <b>서버 고정 정렬</b>(REG_DT DESC, LBL_HSTRY_SN DESC).
     * <p>DEV_FIX(정렬 견고성): 클라이언트 {@code ?sort=<임의필드>} 는 무시한다. 예전엔 요청 sort 를
     * 그대로 이어붙여(and) 매핑 불가 필드가 오면 {@code PropertyReferenceException}→500 이 발생할 수 있었다.
     * 이력 조회 정렬 정책은 '최신순 + 동시각 tiebreaker' 하나뿐이므로 서버에서 고정해 500 을 원천 차단한다.
     */
    private Pageable cappedWithTiebreaker(Pageable pageable) {
        int size = Math.min(pageable.getPageSize(), MAX_HISTORY_PAGE_SIZE);
        Sort sort = Sort.by(Sort.Order.desc("regDt"), Sort.Order.desc("lblHstrySn"));
        return PageRequest.of(pageable.getPageNumber(), size, sort);
    }

    /**
     * 저장 이벤트의 변경 목록 → 관제 통지 changeType 집합 (D-ISSUE-44).
     *
     * <p>이번 저장에 실제로 포함된 종류만 반환한다 — 발행되지 않는 dead 계약값을 없애고, 관제가
     * 종류별 분기를 신뢰할 수 있게 한다. 반환값은 반드시 {@link ChangeType#ALL} 표준 집합에 속한다.
     */
    private Set<String> toChangeTypes(List<LabelChange> changes,
                                      FrameDiscardApplier.Outcome discardOutcome) {
        Set<String> types = new LinkedHashSet<>();
        for (LabelChange change : changes) {
            switch (change.kind()) {
                case ADDED -> types.add(ChangeType.LABEL_ADDED);
                case UPDATED -> types.add(ChangeType.LABEL_UPDATED);
                case DELETED -> types.add(ChangeType.LABEL_DELETED);
            }
        }
        // R4·R5 — 폐기·복원은 라벨 변경과 <b>다른 사실</b>이므로 기존 종류에 욱여넣지 않는다. 한 저장에
        //   라벨 수정과 폐기가 함께 오면 두 종류가 모두 발행되고, 디바운서가 프레임↔종류 페어로 축적해
        //   통지 1건으로 합친다.
        switch (discardOutcome) {
            case DISCARDED -> types.add(ChangeType.FRAME_DISCARDED);
            case RESTORED -> types.add(ChangeType.FRAME_RESTORED);
            case UNCHANGED -> { /* 상태가 바뀌지 않았으면 통지할 사실이 없다 */ }
        }
        return types;
    }


    /**
     * Phase 2 — 요청에 포함된 distinct labelId 들을 LS_LABEL 에서 일괄 조회하여 검증.
     * <ul>
     *   <li>존재하지 않으면 {@link ErrorCode#NOT_FOUND} (모든 항목 공통)</li>
     *   <li>USE_YN='N' 이면 {@link ErrorCode#CONFLICT} — <b>신규 부여에만</b> 적용(C-ISSUE-25)</li>
     * </ul>
     * 모든 labelId 가 null 이면 Repository 호출 skip (early return).
     *
     * <h3>C-ISSUE-25 — soft delete 는 "신규 사용 중지"이지 "저장 전면 차단"이 아니다</h3>
     * 저장 계약이 full-replace 라 프레임의 <b>전체</b> 라벨 세트가 매번 전송된다. 예전에는 요청 items
     * 전체의 labelId 에 USE_YN 검사를 걸어, 사용 중지된 마스터를 참조하는 기존 라벨이 1건이라도 있으면
     * 그 프레임의 <b>모든 저장이 영구히 409</b> 였다(그 라벨을 지우기 전엔 다른 라벨 수정조차 불가).
     * 이제 <b>신규 부여</b>(= 이 프레임에 없던 라벨이거나, 기존 라벨의 labelId 를 바꾸는 경우)에만
     * USE_YN 을 강제하고, 기존 라벨이 기존 labelId 를 그대로 유지하는 것은 통과시킨다.
     *
     * <h3>우회 방지 (IDOR/Mass Assignment)</h3>
     * "id 를 붙였으면 통과"가 아니다. 면제 조건은 ①{@code id} 가 <b>이 프레임(srcSn)의</b> 기존 라벨이고
     * ({@code idIndex} 는 {@code findBySrcSn(srcSn)} 로만 채워진다) ②그 라벨의 {@code labelId} 가
     * <b>실제로 바뀌지 않을 때</b> 뿐이다. 타 프레임/미존재 id 는 {@code idIndex} 에 없어 신규로 취급되어
     * 검사를 받고, 기존 라벨에 사용 중지 마스터를 새로 붙이려는 시도도 labelId 변경이라 거부된다.
     * (요청 {@code labelId==null} 은 "기존 값 유지"라 부여 자체가 없어 검사 대상이 아니다.)
     */
    private void validateAndLoadLabels(List<LabelItemDto> items, Map<Long, LsDataLbl> idIndex) {
        Set<Long> requestedIds = new HashSet<>();
        Set<Long> newlyAssignedIds = new HashSet<>();
        for (LabelItemDto item : items) {
            Long labelId = item.labelId();
            if (labelId == null) {
                continue;
            }
            requestedIds.add(labelId);
            if (isNewLabelAssignment(item, idIndex)) {
                newlyAssignedIds.add(labelId);
            }
        }
        if (requestedIds.isEmpty()) {
            return;
        }
        Map<Long, LsLabel> loaded = lsLabelRepository.findAllById(requestedIds).stream()
                .collect(Collectors.toMap(LsLabel::getLabelId, Function.identity(), (a, b) -> a));
        for (Long requested : requestedIds) {
            LsLabel found = loaded.get(requested);
            if (found == null) {
                throw new CustomException(ErrorCode.NOT_FOUND,
                        "라벨 마스터를 찾을 수 없습니다: labelId=" + requested);
            }
            if (newlyAssignedIds.contains(requested) && !"Y".equals(found.getUseYn())) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "사용 중지된 라벨입니다: labelId=" + requested);
            }
        }
    }

    /**
     * 이 항목이 <b>신규 라벨 생성</b> 대상인지 — 사전 검증과 저장 분기가 공유하는 <b>단일 술어</b>
     * (C-ISSUE-61/62).
     *
     * <p>판정 축은 "그 프레임(srcSn)에 실재하는 라벨인가" 하나다. {@code idIndex} 는
     * {@code findBySrcSn(srcSn)} 로만 채워지므로 ①{@code id == null}(신규) ②타 프레임 id
     * ③아예 존재한 적 없는 id 는 모두 신규다 — 저장 분기가 실제로 그 셋을 <b>현재 프레임의 신규 라벨</b>로
     * 만들기 때문이다. 검증만 {@code id != null} 을 '기존 수정'으로 오분류하면 신규 생성 경로의
     * 좌표 상한({@link #validateWithinBounds})·점 개수 상한({@link #MAX_POINTS_PER_LABEL})이 통째로
     * 우회된다(CWE-20 / CWE-1287). {@link #isNewLabelAssignment}(USE_YN 축)도 같은 축을 쓴다.
     */
    private static boolean isNewLabel(LabelItemDto item, Map<Long, LsDataLbl> idIndex) {
        return item.id() == null || !idIndex.containsKey(item.id());
    }

    /**
     * 이 항목의 {@code labelId} 가 <b>신규 부여</b>인지 판정(C-ISSUE-25 USE_YN 강제 대상).
     * 현재 프레임의 기존 라벨이면서 labelId 가 동일하면 신규 부여가 아니다(그 외는 전부 신규 취급).
     */
    private boolean isNewLabelAssignment(LabelItemDto item, Map<Long, LsDataLbl> idIndex) {
        // id==null(신규) · 타 프레임/미존재 id 는 저장 로직이 '현재 프레임 신규 라벨'로 처리하므로
        // 검사도 신규 기준 — 좌표 상한과 동일한 술어를 공유한다(C-ISSUE-61/62).
        if (isNewLabel(item, idIndex)) {
            return true;
        }
        return !java.util.Objects.equals(idIndex.get(item.id()).getLabelId(), item.labelId());
    }

    /**
     * 좌표 검증 — 빈 입력 / 형식 / 음수 차단.
     * <p>
     * ISSUE-1: 점 개수 상한(MAX_POINTS_PER_LABEL)은 신규 라벨(enforceMaxPoints=true)에만
     * 400 으로 강제한다. 기존 라벨(id != null)은 SAM2 적재 폴리곤(>1000점)이 이미 DB 에 존재할 수
     * 있어, 작업자가 본인이 만들지 않은 라벨 때문에 저장이 전면 차단되던 회귀를 막기 위해 상한
     * 강제 없이 통과시키고 저장 직전 {@link #capPoints}(Douglas-Peucker simplify)로 줄인다.
     * 신규 입력에 상한을 유지함으로써 CWE-770(과대 좌표 DoS) 방어는 보존된다.
     */
    private void validatePoints(String lblTypeCd, List<List<Double>> points, boolean enforceMaxPoints) {
        if (points == null || points.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "points 가 비어있습니다.");
        }
        // SKELETON(키포인트 포즈): 삼중값 전용 검증 경로 (기존 2-튜플 경로와 격리).
        if (LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)) {
            validateSkeletonPoints(points);
            return;
        }
        if (enforceMaxPoints && points.size() > MAX_POINTS_PER_LABEL) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨당 좌표 개수 초과 (최대 " + MAX_POINTS_PER_LABEL + " 점)");
        }
        for (List<Double> pair : points) {
            if (pair == null || pair.size() != 2) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "좌표는 [x, y] 형태여야 합니다.");
            }
            double x = pair.get(0);
            double y = pair.get(1);
            if (x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "좌표는 0 이상이어야 합니다 (x=" + x + ", y=" + y + ")");
            }
        }
    }

    /**
     * C-ISSUE-22 — 좌표 <b>상한</b>(이미지 폭/높이) 검증. 초과 시 400 거부(클램프 아님 — 사용자 확정 정책).
     *
     * <p>{@code bounds == null} 이면(원천 이미지 부재·손상 등으로 실측 불가) 상한 검증만 건너뛴다.
     * 이때 "검증 불가"가 로그 없이 "검증 통과"로 둔갑하지 않도록 {@link FrameBoundsResolver} 가 WARN 을 남긴다.
     *
     * <p><b>좌표 형태 전 분기 커버</b>: 요청 DTO 의 {@code points} 는 항상 {@code List<List<Double>>} 이며
     * ①SKELETON 은 삼중값 {@code [x,y,v]} — x·y 만 검사하고 가시성 {@code v} 는 좌표가 아니므로 제외,
     * ②그 외(BBOX/POLYGON/SEGMENT/TRACK)는 2-튜플 {@code [x,y]} — 두 값 모두 검사한다. 저장 계층의
     * 레거시 포맷(평탄 {@code [x1,y1,...]} / 객체배열 {@code [{"x":..}]})은 <b>요청 표면에 존재하지 않는다</b>
     * (해당 JSON 은 {@code List<List<Double>>} 역직렬화 자체가 실패해 400). 즉 이 두 분기로 전수 커버된다.
     *
     * <p>경계값 정책: {@code x == width}(또는 {@code y == height})는 허용한다 — 우/하단 끝을 가리키는
     * 정상 좌표이며, {@code Sam2SegmentService} 의 외부 응답 검증({@code x > imgWidth})과 동일 기준이다.
     */
    private void validateWithinBounds(String lblTypeCd, List<List<Double>> points, int[] bounds) {
        if (bounds == null || points == null) {
            return;
        }
        int width = bounds[0];
        int height = bounds[1];
        boolean skeleton = LsDataLbl.TYPE_SKELETON.equals(lblTypeCd);
        for (List<Double> tuple : points) {
            // 형식(원소 수/ null)은 validatePoints 가 이미 400 으로 걸렀다 — 여기선 x/y 값만 본다.
            if (tuple == null || tuple.size() < 2) {
                continue;
            }
            Double x = tuple.get(0);
            Double y = tuple.get(1);
            if (x == null || y == null) {
                continue;
            }
            if (skeleton && isUnlabeledKeypoint(tuple)) {
                // v=0(미표기) 키포인트는 좌표를 쓰지 않는 자리표시자(0,0 관례) — 상한 검사 대상 아님.
                continue;
            }
            if (x > width || y > height) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "좌표가 이미지 경계를 벗어났습니다 (x=" + x + ", y=" + y
                                + ", 이미지=" + width + "x" + height + ")");
            }
        }
    }

    /** SKELETON 삼중값의 가시성 {@code v} 가 0(미표기)인지 — 좌표 상한 검사에서 제외할 자리표시자. */
    private static boolean isUnlabeledKeypoint(List<Double> triplet) {
        return triplet.size() >= 3 && triplet.get(2) != null
                && triplet.get(2) == KeypointSerializer.VISIBILITY_MIN;
    }

    /**
     * SKELETON(17-keypoint COCO 포즈) 삼중값 검증 (CWE-20 입력 방어선).
     * <ul>
     *   <li>points 개수 = 정확히 {@value KeypointSerializer#KEYPOINT_COUNT}</li>
     *   <li>각 원소 크기 = 정확히 3 ([x, y, v])</li>
     *   <li>v ∈ {0, 1, 2}</li>
     *   <li>x, y ≥ 0 (v=0 미표기 점은 x,y=0 허용 — 0 은 음수 아님)</li>
     * </ul>
     * 위반 시 400(INVALID_INPUT) fail-secure.
     */
    private void validateSkeletonPoints(List<List<Double>> points) {
        if (points.size() != KeypointSerializer.KEYPOINT_COUNT) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "SKELETON 키포인트는 정확히 " + KeypointSerializer.KEYPOINT_COUNT + " 개여야 합니다.");
        }
        for (List<Double> triplet : points) {
            if (triplet == null || triplet.size() != KeypointSerializer.TRIPLET_SIZE) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "키포인트는 [x, y, v] 형태여야 합니다.");
            }
            // CWE-20: JSON-valid 하지만 원소가 null 인 경우([[10,20,null],...])는 Double→double
            // 언박싱 NPE(→GlobalExceptionHandler catch-all 500)를 유발한다. 언박싱 전에 400 으로
            // fail-secure 거부하여 계약(형식 위반=400)과 일치시킨다.
            Double xBox = triplet.get(0);
            Double yBox = triplet.get(1);
            Double vBox = triplet.get(2);
            if (xBox == null || yBox == null || vBox == null) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "키포인트 좌표에 null 원소가 있습니다.");
            }
            double x = xBox;
            double y = yBox;
            double vRaw = vBox;
            int v = (int) vRaw;
            if (v != vRaw || v < KeypointSerializer.VISIBILITY_MIN || v > KeypointSerializer.VISIBILITY_MAX) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "가시성 v 는 0/1/2 중 하나여야 합니다.");
            }
            if (x < 0 || y < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "좌표는 0 이상이어야 합니다 (x=" + x + ", y=" + y + ")");
            }
        }
    }

    /**
     * 저장용 좌표 직렬화 — {@code LBL_TYPE_CD} 기반 type-route.
     * <ul>
     *   <li>SKELETON: {@link KeypointSerializer#toJson} 삼중값 [[x,y,v], x17] (v 보존)</li>
     *   <li>그 외(BBOX/POLYGON/SEGMENT/TRACK): 기존 2-튜플 {@link LabelPointSerializer#toJson} + capPoints simplify (불변)</li>
     * </ul>
     */
    private String serializePoints(String lblTypeCd, List<List<Double>> points) {
        if (LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)) {
            return KeypointSerializer.toJson(toKeypoints(points), objectMapper);
        }
        return LabelPointSerializer.toJson(capPoints(points), objectMapper);
    }

    /** R9 — AUTO 계열 source 화이트리스트 (온라인 오토라벨 신규 삽입 분기 판정). */
    private static final Set<String> AUTO_SOURCES = Set.of("AUTO_YOLO", "AUTO_SAM2");

    /** source 가 AUTO 계열(온라인 오토라벨)인지 — 아니면(MANUAL/null) 기존 수동 저장 경로. */
    private static boolean isAutoSource(String source) {
        return source != null && AUTO_SOURCES.contains(source);
    }

    /**
     * R9 — AUTO 신규 삽입용 {@link LsDataLbl} 생성. 형태(POLYGON/그 외)에 따라 AUTO 팩토리를 선택해
     * AUTO_LBL_YN='Y' 로 만든다(온라인 오토라벨은 BBOX/POLYGON 만 산출). trackId 는 온라인 단발 검출이라 null.
     */
    private LsDataLbl buildAutoLabel(Long srcSn, LabelItemDto item, String pointsJson, BigDecimal conf) {
        if (LsDataLbl.TYPE_POLYGON.equals(item.lblTypeCd())) {
            return LsDataLbl.createAutoPolygon(srcSn, item.labelId(), item.label(), pointsJson, conf);
        }
        return LsDataLbl.createAutoBbox(srcSn, item.labelId(), item.label(), pointsJson, conf, null);
    }

    /**
     * LS_DATA_LBL_AI_INFO.LBL_SRC_CD 결정 — algorithm 우선, 없으면 source 에서 파생.
     * NOT NULL 컬럼이므로 항상 비-null 을 반환한다(fail-safe: 기본 YOLO).
     */
    private static String resolveAiSource(LabelItemDto item) {
        String algo = item.algorithm();
        if (algo != null && !algo.isBlank()) {
            return normalizeAlgorithm(algo);
        }
        return "AUTO_SAM2".equals(item.source()) ? LsDataLblAiInfo.SRC_SAM2 : LsDataLblAiInfo.SRC_YOLO;
    }

    /** 화이트리스트 algorithm → AI_INFO LBL_SRC_CD 정규화(20자 이하 유지). 미지값은 fail-safe YOLO. */
    private static String normalizeAlgorithm(String algo) {
        return switch (algo) {
            case "SAM2" -> LsDataLblAiInfo.SRC_SAM2;
            case "RT-DETR" -> "RT-DETR";
            default -> LsDataLblAiInfo.SRC_YOLO;
        };
    }

    /** provenance confScore(Double) → BigDecimal(null 보존). 범위 강제는 DTO @Valid + AUTO 팩토리 clamp 이중. */
    private static BigDecimal toScore(Double conf) {
        return conf == null ? null : BigDecimal.valueOf(conf);
    }

    /** 삼중값 nested 리스트 [[x,y,v],...] → KeypointPoint 리스트 (검증 통과 후 호출). */
    private static List<KeypointPoint> toKeypoints(List<List<Double>> nested) {
        List<KeypointPoint> out = new ArrayList<>(nested.size());
        for (List<Double> t : nested) {
            out.add(new KeypointPoint(t.get(0), t.get(1), (int) (double) t.get(2)));
        }
        return out;
    }

    private static List<Point> toPoints(List<List<Double>> nested) {
        List<Point> out = new ArrayList<>(nested.size());
        for (List<Double> pair : nested) {
            out.add(new Point(pair.get(0), pair.get(1)));
        }
        return out;
    }

    /**
     * 저장 직전 좌표 점 개수를 {@link #MAX_POINTS_PER_LABEL} 이하로 단순화한다(ISSUE-1).
     * 상한 이하면 변환만 수행, 초과 시 Douglas-Peucker simplify 로 줄인다.
     */
    private static List<Point> capPoints(List<List<Double>> nested) {
        List<Point> pts = toPoints(nested);
        if (pts.size() <= MAX_POINTS_PER_LABEL) {
            return pts;
        }
        return PolygonSimplifier.simplifyToMax(pts, 1.0, MAX_POINTS_PER_LABEL);
    }
}
