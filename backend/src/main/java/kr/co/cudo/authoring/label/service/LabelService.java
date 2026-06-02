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
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
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

    public LabelService(LsDataLblRepository labelRepository,
                        LsDataLblAiInfoRepository aiInfoRepository,
                        LsDataSrcRepository srcRepository,
                        VideoRepository videoRepository,
                        WorkLockService workLockService,
                        LabelAccessGuard accessGuard,
                        ObjectMapper objectMapper,
                        LsLabelRepository lsLabelRepository,
                        ApplicationEventPublisher eventPublisher,
                        LsRawDataStatusRepository rawDataStatusRepository) {
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
        return LabelResponse.of(current, siblings, labels, frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, objectMapper);
    }

    /** Phase 3 — actor + raw 요청 여부 → frameImageType 결정 (단일 진실의 원천). */
    public static String resolveFrameImageType(TokenClaims actor, boolean allowRaw) {
        if (actor != null && actor.role() == Role.REVIEWER && allowRaw) {
            return "RAW";
        }
        return "DEID";
    }

    /**
     * 프레임 라벨 bulk upsert.
     *  - id == null : 신규 INSERT (AUTO_LBL_YN='N')
     *  - id != null : 기존 UPDATE (AUTO_LBL_YN 유지 — 자동 라벨이라도 'Y' 그대로)
     *  - 요청에 누락된 기존 라벨은 보존 (이번 Phase 정책 — 명시적 DELETE 엔드포인트 별도)
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

        // 좌표 사전 검증 (트랜잭션 내부에서 한꺼번에 실패해도 롤백 — 여기선 명시적으로 미리 차단)
        for (LabelItemDto item : req.items()) {
            validatePoints(item.points());
        }

        // Phase 2 — labelId 사전 검증 (입력에 포함된 모든 labelId 의 존재 + USE_YN='Y' 확인).
        // N+1 회피: distinct labelId 1회 lookup. (응답 enrichment 는 저장 후 result 기준으로 다시 lookup 한다.)
        validateAndLoadLabels(req.items());

        // 기존 라벨 인덱싱 (id 기반 수정용)
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        Map<Long, LsDataLbl> idIndex = new HashMap<>();
        for (LsDataLbl e : existing) {
            idIndex.put(e.getLblSn(), e);
        }

        List<LsDataLbl> result = new ArrayList<>();
        for (LabelItemDto item : req.items()) {
            String pointsJson = LabelPointSerializer.toJson(toPoints(item.points()), objectMapper);
            if (item.id() != null && idIndex.containsKey(item.id())) {
                LsDataLbl found = idIndex.get(item.id());
                if (!found.getSrcSn().equals(srcSn)) {
                    // IDOR 추가 방어 — id 가 다른 프레임의 라벨이면 차단.
                    throw new CustomException(ErrorCode.FORBIDDEN, "다른 프레임의 라벨 ID 입니다.");
                }
                // Phase 2 — labelId 가 null 이면 기존 값 유지, non-null 이면 검증 후 변경.
                found.updateUserContent(item.lblTypeCd(), item.labelId(), item.label(), pointsJson);
                result.add(found);
            } else {
                LsDataLbl created = labelRepository.save(
                        LsDataLbl.createManual(srcSn, item.lblTypeCd(), item.labelId(),
                                item.label(), pointsJson, actorNo));
                result.add(created);
            }
        }
        log.info("[Label] bulkUpsert srcSn={} actor={} count={}", srcSn, actorNo, result.size());
        // TASK_MODIFIED 통지는 검수 완료(APPROVED) 후 수정 시에만 발행한다(CLAUDE.md 작업 단위 통지 정책).
        // 검수 전(PENDING/ASSIGNED/IN_REVIEW/PROCESSING 등) 저장은 일반 작업이므로 통지 미발행.
        if (isReviewApproved(current.getRawSn())) {
            // bulkUpsert 는 신규 INSERT + 기존 UPDATE 를 한 배치에서 함께 처리하며(누락 라벨은 보존,
            // 삭제 없음) 단일 (rawSn, srcSn) 이벤트로는 add/update 를 자명하게 구분할 수 없으므로
            // 계약 표준값 LABEL_UPDATED 하나로 통일한다(억지 분기 금지).
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

        // 라벨 저장(임시저장)은 LS_DATA_LBL upsert + 작업본 갱신만 수행한다.
        // 학습데이터 버전 스냅샷(LS_LABEL_VERSION)은 검수 승인(APPROVED) 시점에만 생성한다(SFR-08).
        // → 저장 시 versionService 자동 커밋을 호출하지 않는다.

        return LabelResponse.of(current, siblings, result, frameImageType, lockSttsCd,
                aiInfoMap, lsLabelMap, objectMapper);
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

    /** 좌표 검증 — 음수 차단 + 점 개수 상한. */
    private void validatePoints(List<List<Double>> points) {
        if (points == null || points.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "points 가 비어있습니다.");
        }
        if (points.size() > MAX_POINTS_PER_LABEL) {
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

    private static List<Point> toPoints(List<List<Double>> nested) {
        List<Point> out = new ArrayList<>(nested.size());
        for (List<Double> pair : nested) {
            out.add(new Point(pair.get(0), pair.get(1)));
        }
        return out;
    }
}
