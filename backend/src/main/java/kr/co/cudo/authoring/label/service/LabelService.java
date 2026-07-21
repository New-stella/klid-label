package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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
    /** 검수 완료(APPROVED) 여부 판정용 영상 상태 조회. */
    private final LsRawDataStatusRepository rawDataStatusRepository;
    /** Phase 2 — 라벨 변경 이력(ADDED/UPDATED/DELETED) 감사 기록/조회. */
    private final LsDataLblHstryRepository labelHistoryRepository;
    /** Phase 2 full-replace — 삭제 라벨의 속성값(자식) 선삭제(FK 고아 방지). */
    private final LsDataLblAttrValRepository attrValRepository;

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
                        LsRawDataStatusRepository rawDataStatusRepository,
                        LsDataLblHstryRepository labelHistoryRepository,
                        LsDataLblAttrValRepository attrValRepository) {
        this.labelRepository = labelRepository;
        this.aiInfoRepository = aiInfoRepository;
        this.srcRepository = srcRepository;
        this.videoRepository = videoRepository;
        this.workLockService = workLockService;
        this.accessGuard = accessGuard;
        this.objectMapper = objectMapper;
        this.lsLabelRepository = lsLabelRepository;
        this.eventPublisher = eventPublisher;
        this.rawDataStatusRepository = rawDataStatusRepository;
        this.labelHistoryRepository = labelHistoryRepository;
        this.attrValRepository = attrValRepository;
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
        List<LsDataLbl> labels = labelRepository.findBySrcSn(srcSn);
        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());
        String frameImageType = resolveFrameImageType(actor, allowRaw);
        // Phase 3 보강 — FE 가 라벨링 화면 진입 시 영상 잠금 상태(LOCKED_FOR_REDEIDENT)를 사전 인지하도록 응답에 포함.
        // 잠금된 영상은 라벨 저장 자체가 차단되므로(아래 bulkUpsert 가드 참조) UI 측 비활성화 단서로 사용된다.
        // hotfix: 전체 row fetch 회피 — lockSttsCd 단일 컬럼 projection 사용 (PK 인덱스 lookup).
        String lockSttsCd = workLockService.isRawLocked(current.getRawSn()) ? "LOCKED" : null;
        // Phase 6 — autoLblYn/confScore/lblSrcCd 는 LS_DATA_LBL_AI_INFO 에서 채움 (N+1 회피 일괄 lookup)
        Map<Long, LsDataLblAiInfo> aiInfoMap = resolveAiInfoMap(labels);
        // Phase 2 — labelName/color 는 LS_LABEL 에서 채움 (N+1 회피 일괄 lookup)
        Map<Long, LsLabel> lsLabelMap = resolveLsLabelMap(labels);
        // R5 — 형제 프레임별 라벨 존재 여부(hasLabel) — 프레임 strip SAVED(연두) 판정용. 프레임 수와
        // 무관하게 IN 절 1회로 라벨 보유 프레임 집합을 조회한다(N+1 금지).
        Set<Long> labeledSrcSns = resolveLabeledSrcSns(siblings);
        return LabelResponse.of(current, siblings, labels, frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, labeledSrcSns, objectMapper);
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
     *  - id == null : 신규 INSERT — source 가 AUTO 계열이면 AUTO_LBL_YN='Y' + LS_DATA_LBL_AI_INFO(신뢰도/알고리즘)
     *                 기록(R9 온라인 오토라벨 출처 보존), 그 외(MANUAL/미지정)는 기존대로 수동 저장(AUTO_LBL_YN='N').
     *  - id != null : 기존 UPDATE (AUTO_LBL_YN 유지 — 자동 라벨이라도 'Y' 그대로, provenance 힌트 무시)
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

        // Phase 3 — 비식별 재처리 중 영상은 라벨 수정 금지 (Race Condition 방어 + 정책)
        if (workLockService.isRawLocked(current.getRawSn())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다.");
        }

        // LOW hardening — 동일 id 가 items 에 중복되면 last-value-wins 로 dedup 한다(같은 라벨 이중 처리·
        // 카운트 중복 방지). id==null(신규)은 모두 유지, non-null id 는 마지막 항목만 유효(그 값이 최종 저장값).
        List<LabelItemDto> items = dedupById(req.items());

        // 좌표 사전 검증 (트랜잭션 내부에서 한꺼번에 실패해도 롤백 — 여기선 명시적으로 미리 차단)
        for (LabelItemDto item : items) {
            // 신규 라벨(id == null)은 점 개수 상한을 강제(CWE-770 DoS 방어). 수동 드로잉/정상 SAM2 결과는
            // 모두 상한 이하이며, SAM2 분할/추적 서비스가 적재 전 simplify 하므로 1000점 초과 신규 입력은 비정상.
            // 기존 라벨(id != null)은 상한 초과여도 저장 직전 simplify 로 보존한다(ISSUE-1, 아래 capPoints).
            // SKELETON 은 삼중값(17점·v∈{0,1,2}) 전용 검증으로 type-route (기존 2-튜플 경로 불변).
            validatePoints(item.lblTypeCd(), item.points(), item.id() == null);
        }

        // Phase 2 — labelId 사전 검증 (입력에 포함된 모든 labelId 의 존재 + USE_YN='Y' 확인).
        // N+1 회피: distinct labelId 1회 lookup. (응답 enrichment 는 저장 후 result 기준으로 다시 lookup 한다.)
        validateAndLoadLabels(items);

        // 기존 라벨 인덱싱 (id 기반 수정용)
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        Map<Long, LsDataLbl> idIndex = new HashMap<>();
        for (LsDataLbl e : existing) {
            idIndex.put(e.getLblSn(), e);
        }

        // full-replace 델타 기준 — 요청에 담긴 non-null id(=생존 대상). 여기에 없는 existing 라벨은 삭제된다.
        Set<Long> reqIds = new HashSet<>();
        for (LabelItemDto item : items) {
            if (item.id() != null) {
                reqIds.add(item.id());
            }
        }

        List<LsDataLbl> result = new ArrayList<>();
        // V114 — 라벨 변경 이력. 저장 판정과 동일 소스(item.id()==null=신규)로 종류를 결정하고,
        // 검증·저장·삭제가 모두 통과한 뒤 동일 트랜잭션에서 저장 이벤트 1건으로 원자 기록한다(HIGH #1).
        List<LabelChange> changes = new ArrayList<>();
        String actorId = String.valueOf(actorNo);
        for (LabelItemDto item : items) {
            // ISSUE-1: 저장 직전 점 개수 상한 적용 (SAM2 적재 폴리곤 등 1000점 초과도 simplify 후 저장).
            // SKELETON 은 KeypointSerializer 삼중값 경로로 직렬화 (기존 2-튜플 toJson 경로 불변).
            String pointsJson = serializePoints(item.lblTypeCd(), item.points());
            if (item.id() != null && idIndex.containsKey(item.id())) {
                // idIndex 는 findBySrcSn(srcSn) 로만 채워지므로(현재 프레임 라벨) 이 분기의 라벨은 항상 srcSn 소유
                // → found.getSrcSn().equals(srcSn) 는 언제나 참이라, 과거의 "다른 프레임 라벨이면 FORBIDDEN"
                //   방어는 도달 불가한 죽은 코드였다(DEV_FIX 로 제거). IDOR 관점에서도 무위험:
                //   진입부 accessGuard.verifyAndGet(srcSn) 로 현재 프레임 소유가 검증되고, 타 프레임/미존재 id 는
                //   여기 진입하지 못한 채 else 로 흘러 '현재 프레임 신규 라벨(ADDED)'로 안전 처리된다(타 프레임 라벨 불변).
                LsDataLbl found = idIndex.get(item.id());
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
        // HIGH #2 — full-replace 대량 삭제 감사 로깅(민감정보 없이 카운트만 — PII/토큰 미출력).
        log.info("[Label] bulkUpsert srcSn={} actor={} existing={} saved={} deleted={}",
                srcSn, actorNo, existing.size(), result.size(), toDelete.size());
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전(PENDING/ASSIGNED/IN_REVIEW/PROCESSING 등) 저장은 일반 작업이므로 통지 미발행.
        // LOW #12 — 무변경(changes 비면) 이면 통지도 미발행.
        if (!changes.isEmpty() && isReviewApproved(current.getRawSn())) {
            // bulkUpsert 는 신규 INSERT + 기존 UPDATE + 삭제(full-replace)를 한 배치에서 함께 처리하며
            // 단일 (rawSn, srcSn) 이벤트로는 종류를 자명하게 구분할 수 없으므로 계약 표준값
            // LABEL_UPDATED 하나로 통일한다(억지 분기 금지 — 관제는 통지 수신 후 상세 API 로 재조회).
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    current.getRawSn(), srcSn, ChangeType.LABEL_UPDATED, actorNo));
        }

        List<LsDataSrc> siblings = srcRepository.findByRawSnOrderByFrameNoAsc(current.getRawSn());
        String frameImageType = resolveFrameImageType(actor, false);
        // Phase 3 보강 — bulkUpsert 통과 시점에는 잠금이 없음이 보장되지만(위 가드)
        // 응답 스키마 일관성을 위해 동일 필드를 반환한다. raw 는 이미 fetch 됨 → 추가 쿼리 없음.
        String lockSttsCd = null;

        // Phase 6 — bulkUpsert 결과에 자동 라벨(수정만 이루어진)이 섞일 수 있으므로 AI Info lookup.
        // 수동 신규 라벨은 row 없음 → 자연스럽게 autoLblYn='N' 응답.
        Map<Long, LsDataLblAiInfo> aiInfoMap = resolveAiInfoMap(result);
        // Phase 2 — 응답 labelName/color enrichment.
        Map<Long, LsLabel> lsLabelMap = resolveLsLabelMap(result);
        // R5 — 저장 직후 응답에도 형제 프레임 hasLabel 반영(방금 저장한 프레임 포함). IN 절 1회(N+1 금지).
        Set<Long> labeledSrcSns = resolveLabeledSrcSns(siblings);

        // 라벨 저장(임시저장)은 LS_DATA_LBL upsert + 작업본 갱신만 수행한다.
        // 학습데이터 버전 스냅샷(LS_LABEL_VERSION)은 검수 승인(APPROVED) 시점에만 생성한다(SFR-08).
        // → 저장 시 versionService 자동 커밋을 호출하지 않는다.

        return LabelResponse.of(current, siblings, result, frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, labeledSrcSns, objectMapper);
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
        accessGuard.verifyAndGet(srcSn, actor);
        Pageable effective = cappedWithTiebreaker(pageable);
        // V114 — 저장 이벤트 행을 그대로 매핑(라벨명 enrichment 는 diff 페이로드로 이관 — Phase 2).
        return labelHistoryRepository.findBySrcSn(srcSn, effective).map(LabelHistoryResponse::from);
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
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정.
     * 상태 row 가 없으면 미검수로 간주하여 false. 매직스트링 금지 — {@link LsRawDataStatus#STTS_APPROVED} 상수 비교.
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    /**
     * Phase 2 — 요청에 포함된 distinct labelId 들을 LS_LABEL 에서 일괄 조회하여 검증.
     * <ul>
     *   <li>존재하지 않으면 {@link ErrorCode#NOT_FOUND}</li>
     *   <li>USE_YN='N' 이면 {@link ErrorCode#CONFLICT} (사용 불가 라벨)</li>
     * </ul>
     * 모든 labelId 가 null 이면 Repository 호출 skip (early return).
     */
    private void validateAndLoadLabels(List<LabelItemDto> items) {
        Set<Long> requestedIds = new HashSet<>();
        for (LabelItemDto item : items) {
            if (item.labelId() != null) {
                requestedIds.add(item.labelId());
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
            if (!"Y".equals(found.getUseYn())) {
                throw new CustomException(ErrorCode.CONFLICT,
                        "사용 중지된 라벨입니다: labelId=" + requested);
            }
        }
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
