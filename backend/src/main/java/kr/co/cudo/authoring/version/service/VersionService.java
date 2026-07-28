package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepositoryCustom;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.KeypointSerializer;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.PolygonSimplifier;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.label.service.LabelService;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 라벨 버전관리 (DB 스냅샷 기반).
 *
 * <p>운영 제약상 외부 버전관리 서버를 둘 수 없어 라벨 버전/이력을 DB({@link LsLabelVersion}) 에만 저장한다.
 * 각 버전은 라벨 전체 JSON 스냅샷({@code LBL_PAYLOAD}) 과 그 SHA-256({@code VERSION_HASH}) 을 보관하며,
 * diff/rollback 은 모두 이 스냅샷을 BE 에서 직접 파싱·비교하여 계산한다.
 *
 * <p><b>버전 스냅샷 생성 트리거(SFR-08):</b> 학습데이터 버전관리는 <b>검수 완료(APPROVED) 단위</b>에 적용된다.
 * 따라서 스냅샷은 라벨러의 라벨 저장(임시저장) 시점이 아니라 <b>검수 승인 시점</b>에만 생성한다
 * ({@link #commitApproved}). 라벨 저장은 작업본 upsert + FE undo/redo 만 담당하고 버전을 만들지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR (CWE-639): list/diff/rollback 모두 {@link LabelAccessGuard} 로 srcSn 소유/배정 검증.
 *       commitApproved 는 REVIEWER 승인 트랜잭션 내부 전용 호출이라 영상(rawSn) 단위로 동작한다.</li>
 *   <li>Race (CWE-362): 같은 srcSn 동시 승인 스냅샷/rollback 시 ACTIVE 행 비관적 잠금으로 직렬화.</li>
 *   <li>CWE-770 DoS: 스냅샷 페이로드 1MB 한도 검증.</li>
 *   <li>Privacy (CWE-359): 라벨 본문은 로그 출력 금지.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VersionService {

    /** CWE-770 — 일반(승인/롤백) 스냅샷 페이로드 최대 크기 (바이트, 1MB). */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    /**
     * R7-1 — 비식별 신고 스냅샷 전용 상향 한도 (바이트, 10MB).
     *
     * <p>비식별 신고는 개인정보 노출 안전장치이므로 <b>어떤 데이터 상태에서도 반드시 성공</b>해야 한다.
     * 영상 전체 라벨(SAM2 폴리곤 다수 포함)이 1MB 를 넘으면 폴리곤 단순화로 실질 크기를 줄이되,
     * 그래도 초과할 수 있으므로 이 경로에 한해 한도를 10MB 로 상향한다.
     * DoS(CWE-770) 방어는 라벨당 좌표 상한({@link LabelService#MAX_POINTS_PER_LABEL})과
     * 메타 수정 건수 상한 등 상류 가드로 유지되며, TEXT 컬럼이라 저장 자체는 안전하다.
     */
    public static final int MAX_DEIDENT_PAYLOAD_BYTES = 10 * 1024 * 1024;

    /** R7-1 — 신고 스냅샷이 1MB 초과 시 폴리곤 단순화에 사용할 초기 epsilon(px). */
    private static final double DEIDENT_SIMPLIFY_EPSILON = 1.0;

    private final LsLabelVersionRepository labelVersionRepository;
    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final ObjectMapper objectMapper;
    /** 롤백이 라벨을 교체하면(APPROVED 영상) TASK_MODIFIED 통지를 트리거하기 위한 이벤트 발행기. */
    private final ApplicationEventPublisher eventPublisher;
    /** 검수 완료(APPROVED) 여부 판정용 영상 상태 조회 — APPROVED 롤백 시 TASK_MODIFIED 발행 조건. */
    private final LsRawDataStatusRepository rawDataStatusRepository;
    /** 롤백 라벨 교체 시 AI 메타(LS_DATA_LBL_AI_INFO) 고아 정리 + 스냅샷 provenance 복원용. */
    private final LsDataLblAiInfoRepository aiInfoRepository;
    /** 롤백 라벨 삭제 전 속성값(LS_DATA_LBL_ATTR_VAL — 실 FK) 선정리용. */
    private final LsDataLblAttrValRepository attrValRepository;
    /** D-ISSUE-21 — 롤백 행위(누가·언제·어느 버전으로) 이력 기록용 기존 이력 축. */
    private final LsDataLblHstryRepository labelHistoryRepository;

    /**
     * DEV_FIX-B(M4) — 롤백 라벨 교체 시 <b>삭제 대상만 선별 detach</b> 하기 위한 영속성 컨텍스트 핸들.
     *
     * <p>{@code deleteAllByIdInBatch} 는 JPQL bulk delete 라 1차 캐시에서 evict 되지 않는다. 그대로 두면
     * 같은 트랜잭션의 후속 {@code findBySrcSn} 이 <b>복원 전 내용</b>을 담은 managed 인스턴스를 돌려준다
     * (행 수만 맞고 내용은 stale). {@code em.clear()} 는 {@link LsLabelVersion} 까지 detach 시켜 버전
     * 활성 전환(dirty-update)을 유실시키므로 쓸 수 없어, 삭제 대상 라벨 엔티티만 골라 detach 한다.
     *
     * <p>{@code @RequiredArgsConstructor} 생성자 인자에 포함되지 않도록 non-final 필드 +
     * {@link PersistenceContext} 주입(기존 단위테스트의 생성자 시그니처 불변).
     */
    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 검수 승인(APPROVED) 시점에 영상(rawSn) 전체의 학습데이터 버전 스냅샷을 생성한다.
     *
     * <p>영상에 속한 모든 프레임({@link LsDataSrc})을 순회하며, 각 프레임의 현재 라벨 상태를
     * 라벨 응답 스냅샷(JSON)으로 직렬화하여 프레임 단위 새 active 버전(saveReason=APPROVED)을 기록한다.
     *
     * <p>정책:
     * <ul>
     *   <li>라벨이 하나도 없는 프레임은 스냅샷을 생성하지 않는다(스킵) — 빈 버전 적재 방지.</li>
     *   <li>멱등: 프레임의 현재 active 가 동일 스냅샷(=동일 versionHash)이면 새 버전을 만들지 않는다
     *       (수정 없이 재승인 시 중복 버전 미생성).</li>
     *   <li>Race(CWE-362): 프레임별 ACTIVE 행 비관적 잠금으로 동시 승인/롤백을 직렬화한다.</li>
     * </ul>
     *
     * <p>호출 컨텍스트: {@code ReviewService.approve()} 의 승인 트랜잭션 내부에서만 호출된다.
     * 인가는 호출 측(REVIEWER)에서 이미 검증되었으므로 프레임 단위 accessGuard 검증은 생략한다.
     *
     * @return 스냅샷 생성/스킵 집계 ({@link CommitResult}). created=새로 생성된 프레임 스냅샷 수,
     *         skipped=라벨이 있으나 직렬화/크기 초과로 스냅샷이 누락된 프레임 수(멱등/빈 프레임은 제외)
     */
    @Transactional("controlTransactionManager")
    public CommitResult commitApproved(Long rawSn, TokenClaims actor) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        if (frames.isEmpty()) {
            log.info("[Version] approved snapshot no frames rawSn={} actor={}", rawSn, actor.sub());
            return CommitResult.EMPTY;
        }

        // N+1 SELECT 회피 — 프레임 루프 내 findBySrcSn 반복 대신 단일 IN 쿼리로 라벨을 일괄 조회한 뒤
        // srcSn 단위로 분류한다.
        List<Long> srcSns = frames.stream().map(LsDataSrc::getSrcSn).toList();
        List<LsDataLbl> allLabels = labelRepository.findBySrcSnIn(srcSns);
        Map<Long, List<LsDataLbl>> labelsBySrcSn = allLabels.stream()
                .collect(java.util.stream.Collectors.groupingBy(LsDataLbl::getSrcSn));
        // 스냅샷 items 순서 결정성 — 조회(heap) 순서에 의존하면 라벨 집합이 같아도 payload 바이트가 달라져
        // VERSION_HASH 가 흔들린다(멱등/diff/롤백의 전제 붕괴). LBL_SN 오름차순으로 고정한다.
        labelsBySrcSn.values().forEach(list -> list.sort(
                Comparator.comparing(LsDataLbl::getLblSn, Comparator.nullsLast(Comparator.naturalOrder()))));
        // 스냅샷에 AI 메타(출처/신뢰도/자동라벨 여부)를 담기 위한 일괄 조회 — 단일 IN 쿼리(N+1 금지).
        Map<Long, LsDataLblAiInfo> aiInfoBySn = loadAiInfo(allLabels);

        int created = 0;
        int skipped = 0;
        for (LsDataSrc frame : frames) {
            List<LsDataLbl> labels = labelsBySrcSn.getOrDefault(frame.getSrcSn(), List.of());
            // 라벨이 없는 프레임은 스냅샷 미생성 (빈 버전 적재 방지) — 스킵 집계에 포함하지 않는다.
            if (labels.isEmpty()) {
                continue;
            }
            FrameSnapshotOutcome outcome =
                    snapshotFrameOnApprove(raw, frame, frames, labels, aiInfoBySn, actor.sub());
            if (outcome == FrameSnapshotOutcome.CREATED) {
                created++;
            } else if (outcome == FrameSnapshotOutcome.SKIPPED) {
                // M-2 — 라벨이 있는데도 직렬화/크기 초과로 스냅샷이 누락된 프레임. 무음 누락 방지를 위해 집계한다.
                skipped++;
            }
        }
        log.info("[Version] approved snapshot rawSn={} frames={} created={} skipped={} actor={}",
                rawSn, frames.size(), created, skipped, actor.sub());
        return new CommitResult(created, skipped);
    }

    /**
     * M-2 — 검수 승인 스냅샷 생성 집계 결과.
     *
     * @param created 새로 생성된 프레임 스냅샷 버전 수 (멱등/빈 프레임 제외)
     * @param skipped 라벨이 존재하지만 직렬화/크기 초과로 스냅샷이 누락된 프레임 수 (무음 누락 가시화 지표)
     */
    public record CommitResult(int created, int skipped) {
        static final CommitResult EMPTY = new CommitResult(0, 0);

        /** 라벨이 있는데도 스냅샷이 1건이라도 누락됐는지 — 승인 API 의 WARN 로깅 조건. */
        public boolean hasSkips() {
            return skipped > 0;
        }
    }

    /** 단일 프레임 스냅샷 처리 결과: 생성/멱등 스킵/누락 스킵. */
    private enum FrameSnapshotOutcome {
        /** 새 active 버전 생성. */
        CREATED,
        /** 현재 active 와 동일 스냅샷이라 멱등 미생성(정상). */
        IDEMPOTENT,
        /** 라벨은 있으나 직렬화/크기 초과로 스냅샷 누락(가시화 대상). */
        SKIPPED
    }

    /**
     * 승인 스냅샷에 담을 AI 메타를 일괄 조회한다 (키: {@code LS_DATA_LBL.LBL_SN}).
     * 라벨이 없으면 빈 IN 절을 만들지 않도록 즉시 빈 맵을 반환한다.
     */
    private Map<Long, LsDataLblAiInfo> loadAiInfo(List<LsDataLbl> labels) {
        if (labels.isEmpty()) {
            return Map.of();
        }
        List<Long> lblSns = labels.stream().map(LsDataLbl::getLblSn).filter(Objects::nonNull).toList();
        if (lblSns.isEmpty()) {
            return Map.of();
        }
        Map<Long, LsDataLblAiInfo> map = new LinkedHashMap<>();
        for (LsDataLblAiInfo info : aiInfoRepository.findByDataLblSnIn(lblSns)) {
            map.putIfAbsent(info.getDataLblSn(), info);
        }
        return map;
    }

    /**
     * 단일 프레임의 현재 라벨을 승인 스냅샷으로 저장한다. 멱등(동일 active 해시) 시 미생성.
     *
     * <p>스냅샷 payload 에는 AI 메타({@code autoLblYn/confScore/lblSrcCd})를 함께 담는다 — 담지 않으면
     * 롤백 복원(D-ISSUE-23)이 항상 "수동 라벨"로 되살아나 오토라벨 출처·신뢰도가 소실된다.
     *
     * @return 처리 결과 — CREATED(생성) / IDEMPOTENT(멱등 미생성) / SKIPPED(직렬화·크기초과 누락)
     */
    private FrameSnapshotOutcome snapshotFrameOnApprove(LsDataRaw raw, LsDataSrc frame, List<LsDataSrc> siblings,
                                           List<LsDataLbl> labels, Map<Long, LsDataLblAiInfo> aiInfoBySn,
                                           String actorId) {
        LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, "DEID", null,
                aiInfoBySn, objectMapper);
        // BE-4 — 라벨/폴리곤이 많은 프레임은 1MB 하드 한도(validatePayloadSize)에 걸려 승인 트랜잭션 전체가
        // 롤백되어 검수 승인 자체가 차단되던 결함을 수정한다. 구 비식별 신고 스냅샷 경로와 동일하게
        // 1MB 초과 시 폴리곤을 단순화하고 상향 한도(10MB)를 적용해 정상 승인이 차단되지 않게 한다.
        // 페이로드 정합성·해시(versionHash) 로직과 APPROVED 전이/스냅샷 동시 커밋 원칙은 그대로 유지한다.
        String payload;
        try {
            payload = serializeSnapshotWithSimplification(snapshot, frame.getSrcSn());
        } catch (CustomException e) {
            // 단순화 후에도 상향 한도(10MB) 초과 — 정상 데이터(라벨당 좌표 상한 상류 적용)에서는 도달하지 않음.
            // 도달 시에도 승인 전체를 막지 않도록 해당 프레임만 스킵 + 경고(본문 미출력).
            log.error("[Version] approved snapshot too large after simplify srcSn={} reason={}",
                    frame.getSrcSn(), e.getMessage());
            return FrameSnapshotOutcome.SKIPPED;
        } catch (Exception e) {
            // 직렬화 실패는 내부 오류 — 승인 자체를 막지 않도록 해당 프레임만 스킵 + 경고(본문 미출력).
            log.error("[Version] approved snapshot serialize failed srcSn={}", frame.getSrcSn(), e);
            return FrameSnapshotOutcome.SKIPPED;
        }
        String versionHash = sha256Hex(payload);

        // Race 직렬화 — ACTIVE 행 비관적 잠금.
        List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
                raw.getRawSn(), frame.getSrcSn(), LsLabelVersion.ACTIVE_YES);

        // 멱등 — 현재 active 가 동일 스냅샷이면 새 버전 생성하지 않음 (수정 없이 재승인).
        for (LsLabelVersion active : activeVersions) {
            if (versionHash.equals(active.getVersionHash())) {
                return FrameSnapshotOutcome.IDEMPOTENT;
            }
        }

        saveActiveVersion(frame, raw, activeVersions, versionHash, payload,
                LsLabelVersion.SAVE_REASON_APPROVED, actorId);
        return FrameSnapshotOutcome.CREATED;
    }

    // D-25 (2026-07-27 정책 반전) — {@code snapshotDeidentReport} 는 제거됐다. 비식별 누락 신고가
    //   라벨을 삭제하지 않으므로(라벨 보존 + 조회 게이트) 삭제 직전 복원 스냅샷을 만들 이유가 없고,
    //   그 스냅샷은 DATA_SRC_SN=NULL(rawSn 스코프)이라 listVersions/rollback(모두 srcSn 스코프)에서
    //   조회·복원 진입점이 없는 write-only 이력이었다(D-ISSUE-25). 기존 DB 행은 남아 있으므로
    //   {@code diff} 의 NULL DATA_SRC_SN 가드(D-ISSUE-26)를 유지한다.

    /**
     * 프레임 버전 목록. S7 (DEV_FIX-A/H4) — <b>의도적으로 비식별 신고 게이트를 적용하지 않는다</b>:
     * 응답({@code VersionItem})은 해시·시각·저장사유·작성자만 담고 {@code LABEL_PAYLOAD}(좌표 본문)를
     * 포함하지 않아 PII 위치 특정 정보가 새지 않는다. 실제 본문을 내려주는 {@link #diff}/{@link #rollback}
     * 에만 게이트를 둔다(목록은 신고 구간에도 이력 확인 용도로 열려 있어야 한다).
     */
    public List<VersionItem> listVersions(Long srcSn, TokenClaims actor) {
        accessGuard.verifyAccess(srcSn, actor);
        List<LsLabelVersion> versions = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        if (versions.isEmpty()) {
            return Collections.emptyList();
        }
        List<VersionItem> items = new ArrayList<>(versions.size());
        for (LsLabelVersion v : versions) {
            items.add(VersionItem.fromLabelVersion(v, LsLabelVersion.ACTIVE_YES.equals(v.getActiveYn())));
        }
        return items;
    }

    /**
     * 두 버전(versionHash) 간 라벨 단위 diff.
     *
     * <p>각 버전의 {@code LBL_PAYLOAD} 를 파싱하여 라벨 단위 ADDED/REMOVED/MODIFIED 를 계산한다.
     * 두 버전이 같은 프레임(srcSn)일 때만 의미 있으므로 다른 경우 빈 결과를 반환한다.
     */
    public DiffResponseDto diff(String fromHash, String toHash, TokenClaims actor) {
        validateHash(fromHash);
        validateHash(toHash);

        LsLabelVersion fromVersion = findByHashOrThrow(fromHash, "from 버전을 찾을 수 없습니다.");
        LsLabelVersion toVersion = findByHashOrThrow(toHash, "to 버전을 찾을 수 없습니다.");

        // D-ISSUE-26 — DATA_SRC_SN 이 NULL 인 버전(구 비식별 신고 rawSn 스코프 스냅샷 — 정책 반전으로
        //   신규 적재는 중단됐으나 기존 행은 DB 에 남아 있다)은 프레임 단위 diff 대상이 아니다. 가드가
        //   없으면 accessGuard → srcRepository.findById(null) 에서 InvalidDataAccessApiUsageException
        //   (미처리 500)이 난다. 인가 검사 전에 400 으로 조기 반환한다(내부 정보 미노출).
        requireFrameScoped(fromVersion, "from");
        requireFrameScoped(toVersion, "to");

        LsDataSrc fromSrc = accessGuard.verifyAndGet(fromVersion.getDataSrcSn(), actor);
        LsDataSrc toSrc = accessGuard.verifyAndGet(toVersion.getDataSrcSn(), actor);

        // S7 (DEV_FIX-A/H4 — HIGH, CWE-359) — diff 응답(LabelDiffDto.ShapeDto)은 before/after 좌표 전문이다.
        //   신고 구간(DE_IDNTF_YN='F')에 라벨 조회만 막고 diff 를 열어두면 같은 좌표가 버전 비교로 새어나간다.
        //   인가(verifyAndGet) 이후 rawSn 단위로 평가하며, 두 버전이 같은 영상이면 1회만 조회한다(N+1 금지).
        accessGuard.requireNotUnderDeidentReport(fromSrc.getRawSn());
        if (!Objects.equals(fromSrc.getRawSn(), toSrc.getRawSn())) {
            accessGuard.requireNotUnderDeidentReport(toSrc.getRawSn());
        }

        if (!Objects.equals(fromVersion.getDataSrcSn(), toVersion.getDataSrcSn())) {
            return new DiffResponseDto(fromHash, toHash, List.of());
        }

        List<LabelDiffDto> labelDiffs = computeLabelDiffs(
                fromVersion.getLabelPayload(), toVersion.getLabelPayload());
        return DiffResponseDto.of(fromHash, toHash, labelDiffs);
    }

    /**
     * D-ISSUE-26 — 프레임 스코프(DATA_SRC_SN non-null) 버전만 diff 대상임을 강제한다.
     *
     * <p>DATA_SRC_SN 이 NULL 인 행은 영상(rawSn) 스코프 레거시 스냅샷이라 프레임 단위 비교/인가가
     * 성립하지 않는다. 400 으로 명시 거부한다(미처리 500 방지 — OWASP A10:2025).
     *
     * @param version 검사 대상 버전
     * @param side    오류 메시지용 위치 라벨("from"/"to") — 사용자 입력이 아닌 상수만 전달
     */
    private void requireFrameScoped(LsLabelVersion version, String side) {
        if (version.getDataSrcSn() == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    side + " 버전은 프레임 단위 비교 대상이 아닙니다.");
        }
    }

    /**
     * 지정 버전(versionHash) 스냅샷으로 롤백한다.
     *
     * <p><b>롤백 시맨틱(완성):</b> 버전 행 active 전환에 더해 <b>대상 스냅샷의 라벨 본문을 작업본
     * ({@code LS_DATA_LBL}) 으로 실제 복원</b>한다. 라벨링 캔버스(GET /frames/{srcSn}/labels)가
     * 롤백 결과를 즉시 반영하며, 검수 재승인 시 export 폴더 JSON(라벨 내용 진실원)에도 반영된다
     * (V114 로 데이터마트 라벨 내용 뷰 V_COMPLETED_LABEL 은 제거되고 export 경로 노출로 대체됨).
     * <ol>
     *   <li>대상 스냅샷 페이로드(JSON)를 파싱해 해당 프레임(srcSn)의 라벨을 추출.</li>
     *   <li>해당 프레임의 기존 LS_DATA_LBL 을 삭제하고 스냅샷 라벨을 <b>옛 lbl_sn 그대로</b> 복원
     *       (D-ISSUE-22 — PK 재발급 시 diff 가 "전량 교체"로 오분류됨. 점유된 PK 만 신규 발급 폴백).</li>
     *   <li>버전 행은 <b>대상 스냅샷 행을 다시 active 로 전환</b>한다(적층 없음 — D-ISSUE-21).
     *       롤백 행위(누가·언제·어느 버전으로)는 {@code LS_DATA_LBL_HSTRY} 롤백 이벤트로 기록한다.</li>
     *   <li>멱등: 현재 active 가 이미 대상 스냅샷이면 <b>라벨을 건드리지 않고</b> 즉시 반환한다
     *       (D-ISSUE-24 — 이력·통지도 발행하지 않는 진짜 no-op).</li>
     * </ol>
     *
     * <p>가드/일관성:
     * <ul>
     *   <li>접근권한 + ACTIVE 비관적 잠금으로 동시성/IDOR 방어(기존 유지).</li>
     *   <li>작업락(LS_AUTH_WORK_LOCK) 잠긴 영상은 롤백 거부(CONFLICT) — 라벨 교체와 비식별 재처리 충돌 차단.</li>
     *   <li>APPROVED(검수 완료) 영상 롤백은 라벨 변경이므로 TASK_MODIFIED(LABEL_UPDATED) 발행.</li>
     *   <li>페이로드 파싱 실패는 부분 적용 없이 전체 롤백 — 손상 스냅샷은 의미 있는 CustomException(INVALID_INPUT).</li>
     *   <li>라벨 교체·버전 전환·통지가 모두 같은 트랜잭션에 묶여 원자적으로 커밋/롤백된다.</li>
     * </ul>
     */
    @Transactional("controlTransactionManager")
    public LsLabelVersion rollback(String versionHash, Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        validateHash(versionHash);
        // 인가는 accessGuard.verifyAndGet 에 위임:
        // REVIEWER 는 통과 / WORKER 는 본인 LABELER 배정 영상만 통과 (CWE-639 IDOR 방어)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);

        LsLabelVersion target = labelVersionRepository.findByDataSrcSnAndVersionHash(srcSn, versionHash)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "롤백 대상 버전을 찾을 수 없습니다."));

        LsDataRaw raw = videoRepository.findById(src.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 작업락 잠긴 영상은 롤백 거부 — 비식별 재처리/라벨 삭제와 라벨 교체가 충돌하지 않도록 차단.
        if (workLockService.isRawLocked(raw.getRawSn())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "작업이 잠긴 영상은 롤백할 수 없습니다.");
        }

        // S7 (DEV_FIX-A/H5 — HIGH) — 작업락만으로는 부족하다. DE_IDNTF_YN='F' 는 <b>배치 실패 경로</b>
        //   (KpstDeidentTxService / BatchTransitionService)에서 작업락 없이도 세팅되므로, 위 락 검사만 두면
        //   "읽기는 막혔는데 쓰기는 열린" 비대칭이 남는다. 그 상태의 롤백은 라벨 본문을 교체하고, APPROVED
        //   영상이면 exportRegenerated=true 로 export 전량 재생성까지 유발한다(재비식별 대기 중 산출물 확정).
        //   조회 게이트와 동일한 단일 지점을 재사용해 신고/비식별 실패 구간에는 롤백도 거부한다.
        accessGuard.requireNotUnderDeidentReport(raw.getRawSn());

        String snapshot = target.getLabelPayload() == null ? "" : target.getLabelPayload();

        // 손상 스냅샷은 부분 적용 없이 전체 롤백 — 라벨 교체 전에 먼저 파싱하여 유효성 확보.
        List<RestoredLabel> restored = parseSnapshotLabels(snapshot);

        // CWE-362 — ACTIVE 행 비관적 잠금을 라벨 교체보다 먼저 획득한다.
        // 잠금을 보유한 상태에서 라벨 교체 → 버전 전환을 수행해야 동시 롤백 시
        // 미잠금 구간에서의 라벨 DELETE+INSERT(데이터 조작)가 직렬화된다.
        // (잠금 순서 규약 — LabelService.bulkUpsert 와 ABBA 데드락 방지: 변경 금지)
        List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
                raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);

        // DEV_FIX-B(H2) — 프레임 행 락을 <b>멱등 판정 전에</b> 취득한다(HIGH, CWE-362).
        //   구 구현은 프레임 락을 replaceFrameLabels 내부(bump)에서야 잡아, 조기 반환 경로는 프레임 락을
        //   전혀 잡지 않았다. 경쟁자 LabelService.bulkUpsert 는 프레임 락만 잡고 버전 행은 잡지 않으므로
        //   두 트랜잭션 사이에 <b>공유 락이 하나도 없었다</b>:
        //     T1 rollback 이 "해시 일치 + 라벨 동일"(frameLabelsMatch, 무잠금 조회)로 판정
        //     → T2 bulkUpsert 가 라벨 교체 커밋 → T1 이 200 no-op 반환("롤백 성공"인데 실제로는 T2 상태).
        //   프레임 락을 먼저 잡으면 판정~복원 전 구간이 bulkUpsert 와 직렬화된다.
        //
        //   ★ 락 순서(ABBA 방지, 변경 금지): LS_LABEL_VERSION → LS_DATA_SRC → LS_DATA_LBL.
        //     기존 상대 순서를 그대로 유지하고 SRC 락의 <b>획득 시점만 앞당겼다</b>(replaceFrameLabels
        //     내부 → 멱등 판정 전). 현재 경로별 순서는
        //       rollback     = VERSION → SRC → LBL
        //       bulkUpsert   =           SRC → LBL
        //       approve      = RAW_DATA_STATUS → VERSION
        //     이라 간선 {VERSION→SRC, SRC→LBL, STATUS→VERSION} 이 DAG 이고 사이클이 없다
        //     (SRC 락을 잡고 VERSION 을 요구하는 경로는 존재하지 않는다).
        //
        //   bump(UPDATE) 가 아니라 스칼라 FOR UPDATE 조회를 쓰는 이유: no-op 경로에서 LBL_VER 을 올리면
        //   "진짜 no-op"(D-ISSUE-24) 이 깨져 열려 있던 편집 화면이 근거 없이 409 를 맞는다. 이 조회는
        //   행 락만 잡고 아무것도 변경하지 않는다(뒤이은 replaceFrameLabels 의 bump 는 이미 보유한 행이라
        //   새 락을 획득하지 않는다).
        srcRepository.lockAndReadLabelVersion(src.getSrcSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // 롤백 결과 스냅샷의 해시 — 대상 스냅샷 payload 만으로 계산되며 라벨 교체 여부와 무관하다(S12).
        String newHash = sha256Hex(snapshot);

        // 복원 값 정규화(타입/라벨명/좌표 JSON)를 한 번만 계산해 멱등 판정과 실제 복원이 동일 값을 쓰게 한다.
        List<NormalizedLabel> normalized = normalize(restored);

        // D-ISSUE-24 — 멱등 판정을 라벨 교체 <b>이전</b>에 수행한다. 이미 동일 스냅샷이 active 이고
        //   작업본 라벨까지 그 스냅샷과 동일하면 아무것도 바꾸지 않고 기존 active 를 반환한다 = 진짜 no-op
        //   (LBL_SN 불변). S6 — no-op 이므로 export 재생성 통지도 발행하지 않는다(불필요한 전량 재생성 차단).
        //   ★ 버전 해시만으로 판정하면 안 된다: 라벨 저장은 버전을 만들지 않으므로(임시저장) active 버전이
        //   대상과 같아도 작업본이 그 사이 바뀌어 있을 수 있고, 그때 건너뛰면 롤백이 조용히 무효가 된다.
        for (LsLabelVersion active : activeVersions) {
            if (newHash.equals(active.getVersionHash()) && frameLabelsMatch(src.getSrcSn(), normalized)) {
                // DEV_FIX-B(M1) — 조기 반환 경로도 <b>잉여 ACTIVE 자기치유</b>를 수행한다.
                //   findActiveForUpdate 가 List 를 돌려주는 이유가 "active 가 2건 이상일 수 있다" 인데,
                //   구 구현은 일치 1건을 찾자마자 return 해 나머지 active 를 그대로 남겼다(교체 경로는
                //   activateRollbackTarget 에서 정리). 판정 결과(어느 행이 정본인지)는 동일하므로
                //   여기서도 정본 외 active 를 비활성화한다.
                deactivateOthers(activeVersions, active);
                log.info("[Version] rollback no-op (already active and labels identical) srcSn={} actor={}",
                        src.getSrcSn(), actor.sub());
                return active;
            }
        }

        // 여기부터는 실질 변경 경로 — 라벨 본문 교체 + 버전 전환 + 이력 + 통지가 한 트랜잭션에 묶인다.
        // 대상 스냅샷의 라벨 본문을 작업본(LS_DATA_LBL)으로 복원 — 기존 프레임 라벨 삭제 후 LBL_SN 보존 복원.
        // (잠금 보유 상태에서 수행)
        List<LabelChange> changes = replaceFrameLabels(src, normalized, actor.sub());

        LsLabelVersion result = activateRollbackTarget(src, activeVersions, target, newHash, actor);

        // D-ISSUE-21 — 되돌리기 행위(누가·언제·어느 버전으로)를 기존 이력 축에 남긴다.
        //   재활성된 버전 행의 REG_ID/REG_DT 는 최초 승인자·승인시각이라 DB 만으로 롤백 행위를 복원할 수 없다.
        labelHistoryRepository.save(LsDataLblHstry.recordRollbackEvent(
                src.getSrcSn(), actor.sub(), target.getVersionHash(), changes));

        // APPROVED(검수 완료) 영상은 라벨 변경이므로 TASK_MODIFIED(LABEL_UPDATED) 발행 (LabelService 패턴 재사용).
        // HIGH-A(Phase 5C) — 롤백은 프레임 라벨을 과거 스냅샷으로 교체하므로 export JSON 도 바뀐다.
        //   exportRegenerated=true 로 발행해 승인 후 수정 경로와 동일하게 export 폴더를 새 버전으로 전량
        //   재생성한 뒤 통지가 나가게 한다(구 4-arg=false 는 롤백 전 라벨로 export 가 고착됐다).
        if (isReviewApproved(raw.getRawSn())) {
            Long actorNo = accessGuard.parseUserNo(actor.sub());
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    raw.getRawSn(), src.getSrcSn(), ChangeType.LABEL_UPDATED, actorNo, true));
        }
        return result;
    }

    /**
     * 롤백 대상 스냅샷 행을 다시 active 로 전환한다 (D-ISSUE-21 — <b>재활성이 정본</b>).
     *
     * <p>롤백 결과 페이로드는 대상 스냅샷 그 자체이므로 재계산 해시({@code newHash})가 항상 대상 행의
     * {@code VERSION_HASH} 와 같다. 따라서 {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 상 새 행을 적층할 수
     * 없고, 구 {@code SAVE_REASON_CD='ROLLBACK'} 적층 분기는 프로덕션에서 도달 불가한 죽은 코드였다(제거).
     * "누가·언제·어느 버전으로" 는 {@link LsDataLblHstry#recordRollbackEvent} 이력이 담당한다.
     *
     * <p>레거시 방어: 저장 해시가 payload 와 불일치하는 행(구 데이터)이면 동일 해시 행 조회가 비므로
     * 대상 행 자체를 재활성한다 — 어떤 경우에도 새 행을 만들지 않는다.
     */
    private LsLabelVersion activateRollbackTarget(LsDataSrc src, List<LsLabelVersion> activeVersions,
                                                  LsLabelVersion target, String newHash, TokenClaims actor) {
        LsLabelVersion reactivated = labelVersionRepository
                .findByDataSrcSnAndVersionHash(src.getSrcSn(), newHash)
                .orElse(target);
        deactivateOthers(activeVersions, reactivated);
        reactivated.activate();
        log.info("[Version] rolled back (reactivated) srcSn={} version={} actor={}",
                src.getSrcSn(), reactivated.getVersionNo(), actor.sub());
        return reactivated;
    }

    /**
     * DEV_FIX-B(M1) — 정본 1건을 제외한 <b>잉여 ACTIVE 행을 비활성화</b>한다(자기치유).
     *
     * <p>{@code findActiveForUpdate} 가 List 를 반환하는 이유가 "부분 유니크가 없어 active 가 2건 이상
     * 존재할 수 있다" 이므로, 교체 경로({@link #activateRollbackTarget})뿐 아니라 <b>멱등 조기 반환
     * 경로</b>에서도 동일하게 정리해야 한다. 판정 결과(어느 행이 정본인가)는 두 경로가 같다.
     *
     * @param actives 잠금 조회된 active 행 목록
     * @param keep    정본으로 유지할 행(이 행은 건드리지 않는다)
     */
    private static void deactivateOthers(List<LsLabelVersion> actives, LsLabelVersion keep) {
        for (LsLabelVersion active : actives) {
            if (!Objects.equals(active.getLabelVersionSn(), keep.getLabelVersionSn())) {
                active.deactivate();
            }
        }
    }

    /**
     * 스냅샷 페이로드(LabelResponse 직렬화 JSON)의 {@code items[]} 를 복원 라벨 목록으로 역직렬화한다.
     *
     * <p>스냅샷 형식(commitApproved 가 생성): {@code {srcSn, frameNo, ..., items:[{id, lblTypeCd,
     * label, labelId, points, autoLblYn, confScore, trackId, lblSrcCd}]}}.
     * {@code id}(= 옛 {@code LBL_SN})까지 보존해 복원 시 <b>식별자를 그대로 되살린다</b>(D-ISSUE-22).
     * AI 메타/트랙(D-ISSUE-23)도 함께 읽는다.
     *
     * <p>하위호환: 옛 스냅샷에는 {@code id/autoLblYn/confScore/trackId/lblSrcCd} 키가 없을 수 있다.
     * 전부 null 허용으로 읽고(null-safe), id 가 없으면 신규 PK 발급으로 복원한다.
     *
     * <p>빈/공백 스냅샷은 라벨 0건(빈 목록)으로 해석 — 빈 상태로의 롤백을 허용한다.
     * 파싱 실패(손상 JSON)는 {@link ErrorCode#INVALID_INPUT} 로 전체 롤백(부분 적용 금지).
     */
    private List<RestoredLabel> parseSnapshotLabels(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(snapshot);
        } catch (Exception e) {
            // 손상 스냅샷 — 내부 오류가 아닌 데이터 무결성 문제이므로 의미 있는 400 으로 전체 롤백.
            log.warn("[Version] rollback snapshot parse failed reason={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "손상된 버전 스냅샷이라 롤백할 수 없습니다.");
        }
        JsonNode items = root.path("items");
        if (items.isMissingNode() || items.isNull()) {
            return List.of();
        }
        if (!items.isArray()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "손상된 버전 스냅샷이라 롤백할 수 없습니다.");
        }
        List<RestoredLabel> result = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            String lblTypeCd = item.path("lblTypeCd").asText(null);
            String label = item.path("label").asText(null);
            Long labelId = item.path("labelId").isIntegralNumber() ? item.get("labelId").asLong() : null;
            // D-ISSUE-22 — 스냅샷의 라벨 식별자(옛 LBL_SN). 없으면 null(신규 발급 복원).
            Long lblSn = item.path("id").isIntegralNumber() ? item.get("id").asLong() : null;
            // SKELETON 은 삼중값 [[x,y,v],x17] 이라 2-튜플 readSnapshotPoints 로는 v 가 유실된다.
            // 롤백 round-trip 무손실을 위해 원본 points 노드 JSON 을 그대로 보존한다(type-route).
            String rawPointsJson = LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)
                    ? item.path("points").toString() : null;
            result.add(new RestoredLabel(lblSn, lblTypeCd, labelId, label,
                    readSnapshotPoints(item.path("points")), rawPointsJson,
                    textOrNull(item.path("autoLblYn")),
                    decimalOrNull(item.path("confScore")),
                    textOrNull(item.path("trackId")),
                    textOrNull(item.path("lblSrcCd"))));
        }
        return result;
    }

    /** 스냅샷 노드에서 문자열을 안전하게 읽는다 — 미존재/null/비문자열/공백은 모두 null(S10 하위호환). */
    private static String textOrNull(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText();
        return text.isBlank() ? null : text;
    }

    /** 스냅샷 노드에서 신뢰도를 안전하게 읽는다 — 미존재/null/비숫자는 null(S10 하위호환). */
    private static BigDecimal decimalOrNull(JsonNode node) {
        return (node != null && node.isNumber()) ? node.decimalValue() : null;
    }

    /**
     * 프레임(src)의 기존 LS_DATA_LBL 을 모두 삭제하고 스냅샷 라벨을 <b>옛 {@code LBL_SN} 그대로</b> 복원한다.
     *
     * <p>D-ISSUE-22 — 구 구현은 {@code createManual} 재생성으로 IDENTITY PK 를 재발급해, 좌표가 동일한
     * 롤백 round-trip 이 diff 에서 "전량 삭제 + 전량 추가"로 오분류됐다(diff 식별 축 = {@code items[].id}).
     * 이제 스냅샷의 {@code id} 를 명시 지정해 삽입한다
     * ({@link LsDataLblRepositoryCustom#insertRestoredWithExplicitIds}).
     *
     * <p>D-ISSUE-23 — 삭제는 고아 방지 순서(ATTR_VAL → AI_INFO → LBL)를 지키고
     * ({@code LabelService.bulkUpsert}/{@code DeidentReportService.deleteAllVideoLabels} 와 동일 규약),
     * 복원은 스냅샷의 {@code TRCK_ID}·AI 메타({@code autoLblYn/confScore/lblSrcCd})까지 되살린다.
     *
     * <p>방어:
     * <ul>
     *   <li><b>PK 충돌</b>: 복원 대상 {@code LBL_SN} 이 다른 라벨(타 프레임)에 점유돼 있으면 그 1건만
     *       신규 PK 발급으로 폴백한다 — 롤백 전체를 실패시키지 않는다(감사 로그, 본문/좌표 미출력).</li>
     *   <li><b>IDENTITY 시퀀스</b>: 명시 삽입 후 시퀀스를 동기화한다(리포지토리가 배치 끝에서 1회 수행).
     *       누락하면 이후 모든 라벨 저장이 PK 충돌로 전면 실패한다.</li>
     *   <li><b>락 순서</b>: 프레임 락 bump 를 라벨 삭제/삽입보다 먼저 수행하고, 삭제·복원·시퀀스 동기화가
     *       모두 호출자의 <b>같은 JPA 트랜잭션·커넥션</b>에서 실행된다(라벨 저장 경로와 ABBA 데드락 없음).</li>
     * </ul>
     *
     * <p>한계(스냅샷이 담지 않는 데이터): 라벨 속성값({@code LS_DATA_LBL_ATTR_VAL})은 스냅샷 페이로드에
     * 없으므로 복원되지 않고, FK 위반(500) 방지를 위해 삭제된다. AI 메타는 스냅샷에 출처
     * ({@code lblSrcCd})가 있을 때만 스냅샷 값으로 교체하고, 없으면 <b>기존 행을 보존</b>한다
     * (옛 스냅샷/출처 미포함 스냅샷으로 provenance 가 소실되지 않게 함 — 날조도 하지 않음).
     *
     * @param actorId AI 메타 복원 행의 REG_ID (감사용 — 좌표/PII 아님)
     * @return 이 교체로 발생한 라벨 변경 목록(ADDED/UPDATED/DELETED) — 롤백 이력 diff 페이로드용(D-ISSUE-21)
     */
    private List<LabelChange> replaceFrameLabels(LsDataSrc src, List<NormalizedLabel> restored, String actorId) {
        Long srcSn = src.getSrcSn();
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        // C-ISSUE-21 — 롤백은 프레임 라벨을 통째로 교체하므로 라벨셋 버전을 +1 한다.
        //   올리지 않으면 롤백 직전 화면을 연 세션이 낡은 세트를 그대로 저장해 롤백을 되돌릴 수 있다.
        // DEV_FIX(H2① 락 순서) — bump 는 프레임 행 쓰기 락을 잡으므로 라벨 삭제/삽입 <b>전에</b> 수행한다
        //   ("프레임 락 → 라벨 락" 규약. 역순이면 라벨 저장 경로와 ABBA 데드락).
        srcRepository.bumpLabelVersionIn(List.of(srcSn));

        // 복원될 식별자 — 같은 LBL_SN 으로 되살아나는 라벨의 AI 메타는 삭제하지 않고 보존한다.
        Set<Long> restoredIds = new LinkedHashSet<>();
        for (NormalizedLabel n : restored) {
            if (n.source().lblSn() != null) {
                restoredIds.add(n.source().lblSn());
            }
        }
        if (!existing.isEmpty()) {
            List<Long> delSns = existing.stream().map(LsDataLbl::getLblSn).toList();
            // 고아 방지 순서: 자식(ATTR_VAL — 실 FK) → 자식(AI_INFO) → 부모(LBL). 모두 bulk delete.
            attrValRepository.deleteByLblSnIn(delSns);
            List<Long> aiDropSns = delSns.stream().filter(id -> !restoredIds.contains(id)).toList();
            if (!aiDropSns.isEmpty()) {
                aiInfoRepository.deleteByDataLblSnIn(aiDropSns);
            }
            labelRepository.deleteAllByIdInBatch(delSns);
            // delete 가 flush 되어 동일 트랜잭션 내 후속 insert(같은 LBL_SN 재삽입 포함)와 분리되도록 보장.
            labelRepository.flush();
            // DEV_FIX-B(M4) — bulk delete(JPQL)는 1차 캐시를 evict 하지 않는다. 삭제 대상 엔티티가 managed
            //   로 남아 있으면, 같은 트랜잭션의 후속 findBySrcSn 이 <b>복원 전 내용</b>을 담은 그 인스턴스를
            //   반환한다(행 수는 맞고 내용만 stale). em.clear() 는 LsLabelVersion 까지 detach 시켜 버전
            //   활성 전환(dirty-update)을 유실시키므로 쓸 수 없어, <b>삭제 대상만 선별 detach</b> 한다.
            detachDeleted(existing);
        }

        List<ResolvedLabel> resolved = restore(srcSn, restored);
        restoreAiInfo(src, resolved, actorId);

        log.info("[Version] rollback restored labels srcSn={} deleted={} created={} idPreserved={}",
                srcSn, existing.size(), resolved.size(),
                resolved.stream().filter(ResolvedLabel::idPreserved).count());
        return diffForHistory(existing, resolved);
    }

    /**
     * DEV_FIX-B(M4) — bulk delete 된 라벨 엔티티만 영속성 컨텍스트에서 분리한다.
     *
     * <p>detach 후에도 인자로 받은 인스턴스의 <b>이미 로딩된 필드는 그대로 읽을 수 있으므로</b>
     * ({@code diffForHistory} 의 before 스냅샷 계산) 이력 정확도에는 영향이 없다. 반대로 detach 하지
     * 않으면 후속 조회가 stale managed 인스턴스를 돌려준다(M4 본문 주석 참조).
     *
     * <p>{@code entityManager} 는 {@link PersistenceContext} 주입 필드라 생성자 기반 단위테스트에서는
     * null 이다. 이 경로는 <b>실 DB 1차 캐시 동작</b>이라 mock 으로 갈음할 수 없어 Testcontainers IT
     * ({@code VersionRollbackRestoreIT}) 가 검증하며, 여기서는 단위테스트를 깨지 않도록 null 을 무시한다.
     */
    private void detachDeleted(List<LsDataLbl> deleted) {
        if (entityManager == null) {
            return;
        }
        for (LsDataLbl e : deleted) {
            if (entityManager.contains(e)) {
                entityManager.detach(e);
            }
        }
    }

    /**
     * 롤백 이력용 라벨 델타 — 교체 전 라벨(existing)과 복원 결과(resolved)를 {@code LBL_SN} 축으로 비교한다.
     *
     * <p>{@code LabelService.bulkUpsert} 의 이력 diff 와 동일한 표현({@link LabelChange} +
     * {@link kr.co.cudo.authoring.version.entity.LabelSnapshot})을 사용해 라벨 이력 화면이 롤백 이벤트도
     * 같은 방식으로 표시할 수 있게 한다. 좌표 문자열은 스냅샷 재직렬화 표현을 그대로 담는다(본문 로그 미출력).
     */
    private static List<LabelChange> diffForHistory(List<LsDataLbl> existing, List<ResolvedLabel> resolved) {
        Map<Long, LsDataLbl> before = new LinkedHashMap<>();
        for (LsDataLbl e : existing) {
            before.put(e.getLblSn(), e);
        }
        List<LabelChange> changes = new ArrayList<>();
        Set<Long> restoredIds = new LinkedHashSet<>();
        for (ResolvedLabel r : resolved) {
            restoredIds.add(r.lblSn());
            LsDataLbl prev = before.get(r.lblSn());
            var after = r.snapshot();
            if (prev == null) {
                changes.add(LabelChange.added(r.lblSn(), after.labelNm(), after));
            } else {
                var prevSnapshot = historySnapshot(prev);
                if (!prevSnapshot.equals(after)) {
                    changes.add(LabelChange.updated(r.lblSn(), after.labelNm(), prevSnapshot, after));
                }
            }
        }
        for (LsDataLbl e : existing) {
            if (!restoredIds.contains(e.getLblSn())) {
                changes.add(LabelChange.deleted(e.getLblSn(), e.getLabelNm(), historySnapshot(e)));
            }
        }
        return changes;
    }

    /**
     * 라벨 엔티티 → 이력 diff 스냅샷 값.
     * <p>{@code entity.LabelSnapshot}(이력 표현)과 본 클래스의 내부 {@code LabelSnapshot}(버전 diff 비교용)은
     * 이름만 같고 용도가 다르므로 정규화된 이름으로 감싼다.
     */
    private static kr.co.cudo.authoring.version.entity.LabelSnapshot historySnapshot(LsDataLbl l) {
        return new kr.co.cudo.authoring.version.entity.LabelSnapshot(
                l.getLblTypeCd(), l.getLabelId(), l.getLabelNm(), l.getPointCn());
    }

    /** 복원 정규화 값 → 이력 diff 스냅샷 값 (실제 적재되는 값과 동일). */
    private static kr.co.cudo.authoring.version.entity.LabelSnapshot historySnapshot(NormalizedLabel n) {
        return new kr.co.cudo.authoring.version.entity.LabelSnapshot(
                n.lblTypeCd(), n.source().labelId(), n.label(), n.pointsJson());
    }

    /**
     * 스냅샷 값(타입/라벨명/좌표 JSON)을 정규화한다 — 멱등 판정과 실제 복원이 <b>동일 값</b>을 쓰도록
     * 한 번만 계산한다(두 삽입 경로 공유).
     */
    private List<NormalizedLabel> normalize(List<RestoredLabel> restored) {
        List<NormalizedLabel> normalized = new ArrayList<>(restored.size());
        for (RestoredLabel r : restored) {
            String lblTypeCd = (r.lblTypeCd() == null || r.lblTypeCd().isBlank())
                    ? LsDataLbl.TYPE_BBOX : r.lblTypeCd();
            String label = (r.label() == null || r.label().isBlank()) ? "label" : r.label();
            // SKELETON 은 스냅샷의 삼중값을 <b>정규 재직렬화</b>해 복원(v 보존 + DB 표현과 동일 형태).
            // 그 외는 기존 2-튜플 재직렬화(불변).
            String pointsJson = LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)
                    ? canonicalKeypointJson(r.rawPointsJson())
                    : LabelPointSerializer.toJson(r.points(), objectMapper);
            normalized.add(new NormalizedLabel(r, lblTypeCd, label, pointsJson));
        }
        return normalized;
    }

    /**
     * DEV_FIX-B(M2) — SKELETON 스냅샷 좌표를 <b>DB 저장 표현과 동일한 정규형</b>으로 재직렬화한다.
     *
     * <p><b>왜 필요한가</b>: 스냅샷 페이로드의 가시성 v 는 {@code LabelResponse} 가 {@code (double) kp.v()}
     * 로 써서 {@code 2.0} 이지만, {@code LS_DATA_LBL.POINT_CN} 은 {@link KeypointSerializer#toJson} 이
     * 정수 {@code 2} 로 쓴다. 구 구현은 SKELETON 만 스냅샷 원본 JSON 문자열을 <b>정규화 없이</b> 그대로
     * 썼기 때문에 문자열 비교(멱등 판정 {@code frameLabelsMatch})가 <b>영구 불일치</b>했다. 그 결과
     * SKELETON 프레임은 같은 해시로 롤백해도 매번 실질 교체 경로를 타 이력 1건 + TASK_MODIFIED
     * (exportRegenerated=true) → export v{n+1} 전량 재생성이 반복됐고, 저장되는 {@code POINT_CN} 의 v 도
     * {@code 2.0} 으로 변질됐다.
     *
     * <p>2-튜플 경로가 {@code LabelPointSerializer.toJson(파싱값)} 으로 정규화하는 것과 <b>대칭</b>이며,
     * 왕복(DB→스냅샷→DB) 후에도 표현이 보존된다.
     *
     * <p>빈/누락 좌표(적대 #9): {@code points} 키가 없는 SKELETON item 은 Jackson MissingNode 의
     * {@code toString()} 이 {@code ""} 라, 정규화 없이 저장하면 {@code POINT_CN=""} 로 <b>키포인트 17개가
     * 예외 없이 소실</b>된다. 손상 스냅샷과 동일하게 400 으로 <b>명시 실패</b>시켜 조용한 소실을 막는다
     * (fail-closed). 형식 위반(삼중값 아님 등)도 같은 400 으로 수렴시킨다.
     */
    private String canonicalKeypointJson(String rawPointsJson) {
        if (rawPointsJson == null || rawPointsJson.isBlank()) {
            log.warn("[Version] rollback skeleton points missing in snapshot — refuse to restore empty keypoints");
            throw new CustomException(ErrorCode.INVALID_INPUT, "손상된 버전 스냅샷이라 롤백할 수 없습니다.");
        }
        try {
            return KeypointSerializer.toJson(
                    KeypointSerializer.fromJson(rawPointsJson, objectMapper), objectMapper);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // CWE-117/209 — 좌표 본문·스택트레이스 미노출(예외 종류만).
            log.warn("[Version] rollback skeleton points normalize failed reason={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "손상된 버전 스냅샷이라 롤백할 수 없습니다.");
        }
    }

    /**
     * 현재 프레임 작업본이 롤백 대상 스냅샷과 <b>완전히 동일</b>한지 — 진짜 no-op 판정용(D-ISSUE-24).
     *
     * <p>비교 축은 {@code LBL_SN} + 라벨 본문(타입/labelId/라벨명/좌표 JSON) 이며, 롤백이 복원하는 대상과
     * 정확히 일치한다. {@code id} 가 없는 옛 스냅샷은 동일성을 판정할 수 없으므로 false(교체 경로)로 둔다.
     */
    private boolean frameLabelsMatch(Long srcSn, List<NormalizedLabel> normalized) {
        Map<Long, kr.co.cudo.authoring.version.entity.LabelSnapshot> expected = new LinkedHashMap<>();
        for (NormalizedLabel n : normalized) {
            Long id = n.source().lblSn();
            if (id == null) {
                return false;
            }
            expected.put(id, historySnapshot(n));
        }
        List<LsDataLbl> current = labelRepository.findBySrcSn(srcSn);
        if (current.size() != expected.size()) {
            return false;
        }
        for (LsDataLbl l : current) {
            var exp = expected.get(l.getLblSn());
            if (exp == null || !exp.equals(historySnapshot(l))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 스냅샷 라벨을 실제 행으로 복원하고 각 라벨의 확정 {@code LBL_SN} 을 돌려준다.
     * {@code id} 보유분은 명시 PK 삽입, 충돌/미보유분은 신규 PK 발급(폴백)으로 처리한다.
     */
    private List<ResolvedLabel> restore(Long srcSn, List<NormalizedLabel> normalized) {
        if (normalized.isEmpty()) {
            return List.of();
        }

        // 2) 옛 LBL_SN 보유분은 명시 PK 로 일괄 삽입 (배치 1회 + 시퀀스 동기화 1회).
        List<LsDataLblRepositoryCustom.RestoreRow> explicitRows = new ArrayList<>();
        for (NormalizedLabel n : normalized) {
            if (n.source().lblSn() != null) {
                explicitRows.add(new LsDataLblRepositoryCustom.RestoreRow(
                        n.source().lblSn(), n.lblTypeCd(), n.source().labelId(), n.label(),
                        n.pointsJson(), n.source().trackId()));
            }
        }
        Set<Long> insertedIds = explicitRows.isEmpty()
                ? Set.of()
                : labelRepository.insertRestoredWithExplicitIds(srcSn, explicitRows);

        // 3) 미삽입분(PK 충돌 또는 id 없는 옛 스냅샷)은 신규 PK 발급으로 폴백 — 전체 실패 금지.
        List<NormalizedLabel> fallbackSources = new ArrayList<>();
        List<LsDataLbl> fallbackEntities = new ArrayList<>();
        List<ResolvedLabel> resolved = new ArrayList<>(normalized.size());
        for (NormalizedLabel n : normalized) {
            Long snapshotId = n.source().lblSn();
            if (snapshotId != null && insertedIds.contains(snapshotId)) {
                resolved.add(new ResolvedLabel(snapshotId, n.source(), true, historySnapshot(n)));
                continue;
            }
            fallbackSources.add(n);
            fallbackEntities.add(LsDataLbl.createRestored(
                    srcSn, n.lblTypeCd(), n.source().labelId(), n.label(), n.pointsJson(),
                    n.source().autoLblYn(), n.source().confScore(),
                    n.source().trackId(), n.source().lblSrcCd()));
        }
        if (!fallbackEntities.isEmpty()) {
            // N+1 INSERT 회피 — 낱건 save 대신 일괄 saveAll 후 flush 로 PK 확보.
            List<LsDataLbl> saved = labelRepository.saveAll(fallbackEntities);
            labelRepository.flush();
            for (int i = 0; i < saved.size(); i++) {
                resolved.add(new ResolvedLabel(saved.get(i).getLblSn(), fallbackSources.get(i).source(),
                        false, historySnapshot(fallbackSources.get(i))));
            }
            long conflicted = fallbackSources.stream().filter(n -> n.source().lblSn() != null).count();
            if (conflicted > 0) {
                // 감사 — 점유된 PK 로 복원 불가했음을 남긴다(좌표/라벨 본문 미출력).
                log.warn("[Version] rollback lblSn conflict — reassigned new ids srcSn={} count={}",
                        srcSn, conflicted);
            }
        }
        return resolved;
    }

    /**
     * 복원된 라벨의 AI 메타({@code LS_DATA_LBL_AI_INFO})를 스냅샷 값으로 되살린다.
     *
     * <p>스냅샷에 출처({@code lblSrcCd})가 있는 라벨만 교체 대상이다. 출처가 없으면
     * (옛 스냅샷/출처 미포함 스냅샷) 기존 행을 그대로 보존한다 — NOT NULL 인 출처를 날조하지 않고,
     * 같은 {@code LBL_SN} 으로 되살아난 라벨의 provenance 를 소실시키지도 않기 위함.
     */
    private void restoreAiInfo(LsDataSrc src, List<ResolvedLabel> resolved, String actorId) {
        List<Long> replaceSns = new ArrayList<>();
        List<LsDataLblAiInfo> rows = new ArrayList<>();
        for (ResolvedLabel r : resolved) {
            String lblSrcCd = r.source().lblSrcCd();
            if (lblSrcCd == null) {
                continue;
            }
            replaceSns.add(r.lblSn());
            rows.add(LsDataLblAiInfo.createRestored(r.lblSn(), src.getRawSn(), src.getSrcSn(),
                    lblSrcCd, r.source().confScore(), r.source().autoLblYn(), actorId));
        }
        if (rows.isEmpty()) {
            return;
        }
        // 확정된 LBL_SN 기준으로만 삭제한다 — 폴백(신규 PK)된 라벨의 옛 id 는 타 프레임 소유일 수 있어
        // 스냅샷 id 로 지우면 남의 AI 메타를 삭제한다.
        aiInfoRepository.deleteByDataLblSnIn(replaceSns);
        aiInfoRepository.saveAll(rows);
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정 — APPROVED 롤백 시 TASK_MODIFIED 발행 조건.
     * 상태 row 가 없으면 미검수로 간주하여 false (LabelService.isReviewApproved 와 동일 정책).
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }

    /**
     * 스냅샷 item 의 {@code points} 노드를 {@link Point} 리스트로 변환한다.
     * 미존재/null/비배열/형식 불일치 좌표쌍은 안전하게 무시(빈 좌표 허용) — 손상으로 간주하지 않는다.
     * 좌표쌍이 [x,y] 숫자쌍이 아닌 경우만 건너뛰어 부분 복원을 허용하되, 음수 등 값 검증은
     * commitApproved 스냅샷이 이미 정상 좌표만 담고 있어 별도 차단하지 않는다.
     */
    private static List<Point> readSnapshotPoints(JsonNode pointsNode) {
        if (pointsNode == null || !pointsNode.isArray()) {
            return List.of();
        }
        List<Point> out = new ArrayList<>(pointsNode.size());
        for (JsonNode pair : pointsNode) {
            if (pair.isArray() && pair.size() >= 2
                    && pair.get(0).isNumber() && pair.get(1).isNumber()) {
                out.add(new Point(pair.get(0).asDouble(), pair.get(1).asDouble()));
            }
        }
        return out;
    }

    /**
     * 롤백 시 스냅샷에서 복원할 라벨 1건. 외부 노출 없음.
     *
     * @param lblSn         스냅샷의 옛 {@code LBL_SN}(= items[].id). 명시 PK 복원 대상. 옛 스냅샷은 null.
     * @param rawPointsJson SKELETON 삼중값 원본 points JSON(v 보존용). 2-튜플 타입은 null.
     * @param autoLblYn     스냅샷의 자동라벨 여부 (없으면 null)
     * @param confScore     스냅샷의 신뢰도 (없으면 null)
     * @param trackId       스냅샷의 트랙 ID — {@code LS_DATA_LBL.TRCK_ID} 실 컬럼 (없으면 null)
     * @param lblSrcCd      스냅샷의 라벨 출처 — {@code LS_DATA_LBL_AI_INFO} 적재 조건 (없으면 null)
     */
    private record RestoredLabel(Long lblSn, String lblTypeCd, Long labelId, String label,
                                 List<Point> points, String rawPointsJson,
                                 String autoLblYn, BigDecimal confScore,
                                 String trackId, String lblSrcCd) {
    }

    /** 복원 값 정규화 결과(타입/라벨명/좌표 JSON 확정) — 명시 삽입·폴백 두 경로가 공유한다. */
    private record NormalizedLabel(RestoredLabel source, String lblTypeCd, String label, String pointsJson) {
    }

    /**
     * 복원 확정 결과 — 실제 적재된 {@code LBL_SN} 과 원본 스냅샷 항목.
     *
     * @param idPreserved 스냅샷의 옛 {@code LBL_SN} 이 그대로 유지됐는지 (false = PK 충돌/무 id 폴백)
     * @param snapshot    실제 적재된 값(정규화 후 타입/labelId/라벨명/좌표) — 롤백 이력 diff 의 after
     */
    private record ResolvedLabel(Long lblSn, RestoredLabel source, boolean idPreserved,
                                 kr.co.cudo.authoring.version.entity.LabelSnapshot snapshot) {
    }

    // ---------- 내부 ----------

    private LsLabelVersion saveActiveVersion(LsDataSrc src, LsDataRaw raw,
                                             List<LsLabelVersion> currentActive,
                                             String versionHash, String labelPayload,
                                             String reasonCd, String actorId) {
        currentActive.forEach(LsLabelVersion::deactivate);
        int nextVersion = labelVersionRepository.countByDataRawSnAndDataSrcSn(
                raw.getRawSn(), src.getSrcSn()) + 1;
        return labelVersionRepository.save(LsLabelVersion.create(
                raw.getRawSn(), src.getSrcSn(), versionHash, labelPayload,
                nextVersion, reasonCd, actorId));
    }

    private LsLabelVersion findByHashOrThrow(String versionHash, String notFoundMessage) {
        List<LsLabelVersion> matches = labelVersionRepository.findByVersionHash(versionHash);
        if (matches.isEmpty()) {
            throw new CustomException(ErrorCode.NOT_FOUND, notFoundMessage);
        }
        return matches.get(0);
    }

    /**
     * BE-4 / R7-1 — 라벨 스냅샷을 직렬화하되 1MB(일반 한도) 초과 시 폴리곤을 단순화한 뒤 상향
     * 한도({@link #MAX_DEIDENT_PAYLOAD_BYTES} 10MB)를 적용한다.
     *
     * <p>검수 승인 스냅샷(commitApproved)이 이 경로를 사용한다(구 비식별 신고 스냅샷 경로는 D-25 정책
     * 반전으로 제거됨). 라벨/폴리곤이 많은 영상에서 1MB 하드 한도로 정상 승인이 차단되지 않도록,
     * 단순화로 실질 크기를 줄이고 그래도 초과하면 10MB 까지 허용한다. DoS(CWE-770) 방어는 라벨당 좌표
     * 상한({@link LabelService#MAX_POINTS_PER_LABEL})과 상류 가드로 유지되며 TEXT 컬럼이라 저장은 안전하다.
     *
     * @param response 직렬화할 라벨 스냅샷
     * @param logId    로깅용 식별자(srcSn 또는 rawSn — 본문/PII 미포함)
     * @return 직렬화된 페이로드 (10MB 초과 시 {@link CustomException}(INVALID_INPUT))
     */
    private String serializeSnapshotWithSimplification(LabelResponse response, Long logId) {
        String payload = writeSnapshotOrThrow(logId, response);
        if (utf8Bytes(payload) <= MAX_PAYLOAD_BYTES) {
            return payload;
        }
        // 1MB 초과 — 폴리곤 단순화 후 재직렬화로 실질 크기 축소.
        LabelResponse simplified = simplifyPolygons(response);
        payload = writeSnapshotOrThrow(logId, simplified);
        int bytes = utf8Bytes(payload);
        log.info("[Version] snapshot simplified id={} bytes={}", logId, bytes);
        if (bytes > MAX_DEIDENT_PAYLOAD_BYTES) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨 스냅샷 크기 초과 (최대 " + MAX_DEIDENT_PAYLOAD_BYTES + " 바이트)");
        }
        return payload;
    }

    private String writeSnapshotOrThrow(Long logId, LabelResponse snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // 직렬화 실패는 내부 오류 — 흐름 전체를 안전하게 막기 위해 예외 전파(트랜잭션 롤백).
            log.error("[Version] snapshot serialize failed id={}", logId, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "라벨 스냅샷 직렬화에 실패했습니다.");
        }
    }

    private static int utf8Bytes(String s) {
        // byte[] 복사 없이 인코딩된 바이트 길이만 계산 (대용량 페이로드 메모리 절감).
        return StandardCharsets.UTF_8.encode(s).remaining();
    }

    /**
     * R7-1 — LabelResponse 의 각 라벨 좌표를 {@link PolygonSimplifier} 로 단순화한 복사본을 만든다.
     * 좌표 외 필드(식별자/라벨명/색상 등)는 그대로 보존한다 (복원 이력의 식별성 유지).
     */
    private static LabelResponse simplifyPolygons(LabelResponse src) {
        List<LabelResponse.Item> items = new ArrayList<>(src.items().size());
        for (LabelResponse.Item it : src.items()) {
            List<List<Double>> points = it.points();
            List<List<Double>> reduced = points;
            if (points != null && points.size() > LabelService.MAX_POINTS_PER_LABEL) {
                List<Point> pts = new ArrayList<>(points.size());
                for (List<Double> p : points) {
                    pts.add(new Point(p.get(0), p.get(1)));
                }
                List<Point> simplified = PolygonSimplifier.simplifyToMax(
                        pts, DEIDENT_SIMPLIFY_EPSILON, LabelService.MAX_POINTS_PER_LABEL);
                reduced = new ArrayList<>(simplified.size());
                for (Point p : simplified) {
                    reduced.add(List.of(p.x(), p.y()));
                }
            }
            items.add(new LabelResponse.Item(
                    it.id(), it.lblTypeCd(), it.label(), it.labelId(), it.labelName(),
                    it.color(), reduced, it.autoLblYn(), it.confScore(), it.trackId(), it.lblSrcCd()));
        }
        return new LabelResponse(src.srcSn(), src.frameNo(), src.videoId(),
                src.frameImageType(), src.lockSttsCd(), src.labelVersion(), src.siblings(), items);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 두 라벨 JSON 스냅샷을 라벨 단위로 비교한다.
     * 라벨 식별자: 응답 snapshot 의 {@code items[].id} (= LS_DATA_LBL.LBL_SN).
     *
     * <ul>
     *   <li>from 에 없고 to 에 있음 → {@link LabelDiffDto.DiffType#ADDED}</li>
     *   <li>from 에 있고 to 에 없음 → {@link LabelDiffDto.DiffType#REMOVED}</li>
     *   <li>양쪽 존재 + 좌표/lblTypeCd/label 차이 → {@link LabelDiffDto.DiffType#MODIFIED}</li>
     * </ul>
     *
     * <p>JSON 파싱 실패는 빈 리스트 반환 (장애 격리).
     */
    private List<LabelDiffDto> computeLabelDiffs(String fromContent, String toContent) {
        if ((fromContent == null || fromContent.isBlank())
                && (toContent == null || toContent.isBlank())) {
            return List.of();
        }
        try {
            Map<String, LabelSnapshot> fromMap = parseLabelsById(fromContent);
            Map<String, LabelSnapshot> toMap = parseLabelsById(toContent);
            return diffLabelMaps(fromMap, toMap);
        } catch (Exception e) {
            log.warn("[Version] label diff parse failed reason={}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 라벨 응답 snapshot JSON ({@code {srcSn, frameNo, items:[{id, lblTypeCd, label, points, ...}]}}) 을
     * id → LabelSnapshot 맵으로 변환. id 가 null/누락이면 skip.
     */
    private Map<String, LabelSnapshot> parseLabelsById(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("labels JSON parse failed", e);
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return Map.of();
        }
        Integer frameNo = root.path("frameNo").isInt() ? root.get("frameNo").asInt() : null;
        Map<String, LabelSnapshot> result = new LinkedHashMap<>();
        for (JsonNode item : items) {
            JsonNode idNode = item.path("id");
            if (idNode.isMissingNode() || idNode.isNull()) {
                continue;
            }
            String id = idNode.asText();
            String lblTypeCd = item.path("lblTypeCd").asText(null);
            String label = item.path("label").asText(null);
            List<List<Double>> points = readPoints(item.path("points"), lblTypeCd);
            result.put(id, new LabelSnapshot(id, lblTypeCd, label, points, frameNo));
        }
        return result;
    }

    /**
     * 좌표 노드를 diff 비교용 nested 리스트로 읽는다.
     *
     * <p>SKELETON(키포인트 포즈)은 삼중값 [[x,y,v],...] 이므로 3번째 원소(v, 가시성)까지 읽어 비교
     * 대상에 포함한다. v 만 바뀐 두 APPROVED 버전이 MODIFIED 로 감지되도록 하기 위함(v-blindness 수정).
     * 그 외 타입(BBOX/POLYGON/SEGMENT/TRACK)은 기존과 동일하게 2-튜플 [x,y] 만 읽는다(경로 불변).
     */
    private static List<List<Double>> readPoints(JsonNode pointsNode, String lblTypeCd) {
        if (!pointsNode.isArray()) {
            return List.of();
        }
        boolean skeleton = LsDataLbl.TYPE_SKELETON.equals(lblTypeCd);
        List<List<Double>> out = new ArrayList<>(pointsNode.size());
        for (JsonNode pair : pointsNode) {
            if (pair.isArray() && pair.size() >= 2
                    && pair.get(0).isNumber() && pair.get(1).isNumber()) {
                if (skeleton && pair.size() >= 3 && pair.get(2).isNumber()) {
                    out.add(List.of(pair.get(0).asDouble(), pair.get(1).asDouble(), pair.get(2).asDouble()));
                } else {
                    out.add(List.of(pair.get(0).asDouble(), pair.get(1).asDouble()));
                }
            }
        }
        return out;
    }

    /** 두 라벨 맵 비교 → ADDED/REMOVED/MODIFIED 분류. 동일 내용은 결과에 포함하지 않음. */
    private static List<LabelDiffDto> diffLabelMaps(Map<String, LabelSnapshot> fromMap,
                                                    Map<String, LabelSnapshot> toMap) {
        List<LabelDiffDto> result = new ArrayList<>();
        for (Map.Entry<String, LabelSnapshot> e : fromMap.entrySet()) {
            LabelSnapshot before = e.getValue();
            LabelSnapshot after = toMap.get(e.getKey());
            if (after == null) {
                result.add(new LabelDiffDto(
                        LabelDiffDto.DiffType.REMOVED,
                        before.frameNo(),
                        before.id(),
                        LabelDiffDto.ShapeDto.fromPoints(before.lblTypeCd(), before.points()),
                        null));
            } else if (!before.equalsContent(after)) {
                result.add(new LabelDiffDto(
                        LabelDiffDto.DiffType.MODIFIED,
                        after.frameNo() != null ? after.frameNo() : before.frameNo(),
                        before.id(),
                        LabelDiffDto.ShapeDto.fromPoints(before.lblTypeCd(), before.points()),
                        LabelDiffDto.ShapeDto.fromPoints(after.lblTypeCd(), after.points())));
            }
        }
        for (Map.Entry<String, LabelSnapshot> e : toMap.entrySet()) {
            if (!fromMap.containsKey(e.getKey())) {
                LabelSnapshot after = e.getValue();
                result.add(new LabelDiffDto(
                        LabelDiffDto.DiffType.ADDED,
                        after.frameNo(),
                        after.id(),
                        null,
                        LabelDiffDto.ShapeDto.fromPoints(after.lblTypeCd(), after.points())));
            }
        }
        return result;
    }

    /** 비교용 라벨 스냅샷 — 외부 노출 없음. */
    private record LabelSnapshot(String id, String lblTypeCd, String label,
                                 List<List<Double>> points, Integer frameNo) {
        boolean equalsContent(LabelSnapshot other) {
            return Objects.equals(lblTypeCd, other.lblTypeCd)
                    && Objects.equals(label, other.label)
                    && Objects.equals(points, other.points);
        }
    }

    /** versionHash 형식 검증 — hex 64자 이하 (SHA-256). Path Manipulation/Injection 방어. */
    private static void validateHash(String hash) {
        if (hash == null || hash.isEmpty() || hash.length() > 64) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 버전 해시 형식입니다.");
        }
        for (int i = 0; i < hash.length(); i++) {
            char c = hash.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 버전 해시 형식입니다.");
            }
        }
    }

    public static boolean isCommittable(TokenClaims actor) {
        return actor != null && actor.channel() != Channel.PORTAL;
    }
}
