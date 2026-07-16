package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.TokenClaims;
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
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        Map<Long, List<LsDataLbl>> labelsBySrcSn = labelRepository.findBySrcSnIn(srcSns).stream()
                .collect(java.util.stream.Collectors.groupingBy(LsDataLbl::getSrcSn));

        int created = 0;
        int skipped = 0;
        for (LsDataSrc frame : frames) {
            List<LsDataLbl> labels = labelsBySrcSn.getOrDefault(frame.getSrcSn(), List.of());
            // 라벨이 없는 프레임은 스냅샷 미생성 (빈 버전 적재 방지) — 스킵 집계에 포함하지 않는다.
            if (labels.isEmpty()) {
                continue;
            }
            FrameSnapshotOutcome outcome = snapshotFrameOnApprove(raw, frame, frames, labels, actor.sub());
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
     * 단일 프레임의 현재 라벨을 승인 스냅샷으로 저장한다. 멱등(동일 active 해시) 시 미생성.
     *
     * @return 처리 결과 — CREATED(생성) / IDEMPOTENT(멱등 미생성) / SKIPPED(직렬화·크기초과 누락)
     */
    private FrameSnapshotOutcome snapshotFrameOnApprove(LsDataRaw raw, LsDataSrc frame, List<LsDataSrc> siblings,
                                           List<LsDataLbl> labels, String actorId) {
        LabelResponse snapshot = LabelResponse.of(frame, siblings, labels, objectMapper);
        // BE-4 — 라벨/폴리곤이 많은 프레임은 1MB 하드 한도(validatePayloadSize)에 걸려 승인 트랜잭션 전체가
        // 롤백되어 검수 승인 자체가 차단되던 결함을 수정한다. 비식별 신고(snapshotDeidentReport)와 동일하게
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

    /**
     * 비식별 누락 신고로 영상 전체 라벨을 삭제하기 직전에, 복원 가능한 전체 라벨 스냅샷을 기록한다(R1 v1.14).
     *
     * <p>기존 {@code LS_DATA_LBL_HSTRY} 는 LBL_SN/SRC_SN/REGISTERED_AT 만 보유하여 좌표 복원이 불가하므로,
     * 영상 단위 라벨 전체를 JSON 스냅샷({@code LBL_PAYLOAD})으로 직렬화해 {@link LsLabelVersion} 에
     * {@code SAVE_REASON_CD='DEIDENT_REPORT'}, {@code ACTIVE_YN='N'} 로 적재한다.
     *
     * <p>정책:
     * <ul>
     *   <li>라벨 0건이면 스냅샷을 만들지 않고 false 반환(빈 버전 적재 방지) — 호출 측이 삭제도 스킵.</li>
     *   <li>ACTIVE_YN='N' 적재라 diff/rollback(APPROVED active 대상)과 간섭하지 않는다.</li>
     *   <li>CWE-770: 스냅샷 페이로드 1MB 한도 검증.</li>
     *   <li>본 메서드는 호출 측(DeidentReportService.report)의 단일 트랜잭션에 참여하여
     *       스냅샷·이력·삭제가 함께 커밋/롤백되도록 한다.</li>
     * </ul>
     *
     * @return 스냅샷을 생성했으면 true, 라벨 0건으로 스킵했으면 false
     */
    @Transactional("controlTransactionManager")
    public boolean snapshotDeidentReport(Long rawSn, TokenClaims actor) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        String actorId = actor == null ? "system" : actor.sub();
        List<LsDataLbl> labels = labelRepository.findAllByRawSn(rawSn);
        if (labels.isEmpty()) {
            log.info("[Version] deident-report snapshot skipped (no labels) rawSn={} actor={}", rawSn, actorId);
            return false;
        }
        String payload = serializeDeidentSnapshot(rawSn, labels);
        String versionHash = sha256Hex(payload);
        int nextVersion = labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(rawSn)
                .map(v -> v.getVersionNo() + 1)
                .orElse(1);
        labelVersionRepository.save(LsLabelVersion.createInactiveRawSnapshot(
                rawSn, versionHash, payload, nextVersion,
                LsLabelVersion.SAVE_REASON_DEIDENT_REPORT, actorId));
        log.info("[Version] deident-report snapshot saved rawSn={} labels={} version={} actor={}",
                rawSn, labels.size(), nextVersion, actorId);
        return true;
    }

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
        accessGuard.verifyAccess(fromVersion.getDataSrcSn(), actor);
        accessGuard.verifyAccess(toVersion.getDataSrcSn(), actor);

        if (!Objects.equals(fromVersion.getDataSrcSn(), toVersion.getDataSrcSn())) {
            return new DiffResponseDto(fromHash, toHash, List.of());
        }

        List<LabelDiffDto> labelDiffs = computeLabelDiffs(
                fromVersion.getLabelPayload(), toVersion.getLabelPayload());
        return DiffResponseDto.of(fromHash, toHash, labelDiffs);
    }

    /**
     * 지정 버전(versionHash) 스냅샷으로 롤백한다.
     *
     * <p><b>롤백 시맨틱(완성):</b> 버전 행 active 전환에 더해 <b>대상 스냅샷의 라벨 본문을 작업본
     * ({@code LS_DATA_LBL}) 으로 실제 복원</b>한다. 라벨링 캔버스(GET /frames/{srcSn}/labels)와
     * 데이터마트 View(V_COMPLETED_LABEL — LS_DATA_LBL 기반)가 롤백 결과를 즉시 반영한다.
     * <ol>
     *   <li>대상 스냅샷 페이로드(JSON)를 파싱해 해당 프레임(srcSn)의 라벨을 추출.</li>
     *   <li>해당 프레임의 기존 LS_DATA_LBL 을 삭제하고 스냅샷 라벨을 재생성(lbl_sn 재발급 허용).</li>
     *   <li>버전 행은 새 active(saveReason=ROLLBACK) 로 기록(R12-1 동일 해시 시 기존 행 재활성).</li>
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

        String snapshot = target.getLabelPayload() == null ? "" : target.getLabelPayload();

        // 손상 스냅샷은 부분 적용 없이 전체 롤백 — 라벨 교체 전에 먼저 파싱하여 유효성 확보.
        List<RestoredLabel> restored = parseSnapshotLabels(snapshot);

        // CWE-362 — ACTIVE 행 비관적 잠금을 라벨 교체보다 먼저 획득한다.
        // 잠금을 보유한 상태에서 라벨 교체 → 버전 전환을 수행해야 동시 롤백 시
        // 미잠금 구간에서의 라벨 DELETE+INSERT(데이터 조작)가 직렬화된다.
        List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
                raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);

        // 대상 스냅샷의 라벨 본문을 작업본(LS_DATA_LBL)으로 복원 — 기존 프레임 라벨 삭제 후 재생성.
        // (잠금 보유 상태에서 수행)
        replaceFrameLabels(src, restored);

        // 롤백 결과 스냅샷의 해시 — 같은 프레임 내 멱등성을 위해 재계산.
        String newHash = sha256Hex(snapshot);

        LsLabelVersion result = upsertRollbackVersion(src, raw, activeVersions, newHash, snapshot, actor);

        // APPROVED(검수 완료) 영상은 라벨 변경이므로 TASK_MODIFIED(LABEL_UPDATED) 발행 (LabelService 패턴 재사용).
        if (isReviewApproved(raw.getRawSn())) {
            Long actorNo = accessGuard.parseUserNo(actor.sub());
            eventPublisher.publishEvent(new TaskModifiedEvent(
                    raw.getRawSn(), src.getSrcSn(), ChangeType.LABEL_UPDATED, actorNo));
        }
        return result;
    }

    /**
     * 버전 행을 롤백 결과로 갱신한다. 멱등/UNIQUE 충돌 처리는 기존 R12-1 로직 유지.
     * <ul>
     *   <li>현재 active 가 이미 롤백 결과와 동일 스냅샷이면 멱등 — 기존 active 반환(새 행 미생성).</li>
     *   <li>R12-1 — 롤백 결과 해시가 같은 프레임 내 기존 버전과 동일하면 신규 INSERT 시
     *       (DATA_SRC_SN, VERSION_HASH) UNIQUE 충돌이 나므로 기존 행을 active 로 복원.</li>
     *   <li>그 외엔 새 active(saveReason=ROLLBACK) 버전을 INSERT.</li>
     * </ul>
     */
    private LsLabelVersion upsertRollbackVersion(LsDataSrc src, LsDataRaw raw,
                                                 List<LsLabelVersion> activeVersions,
                                                 String newHash, String snapshot, TokenClaims actor) {
        // 현재 active 가 이미 롤백 대상과 동일 스냅샷이면 멱등 — 기존 active 반환.
        for (LsLabelVersion active : activeVersions) {
            if (newHash.equals(active.getVersionHash())) {
                log.info("[Version] rollback idempotent srcSn={} hash={} actor={}",
                        src.getSrcSn(), newHash, actor.sub());
                return active;
            }
        }

        Optional<LsLabelVersion> existingSameHash =
                labelVersionRepository.findByDataSrcSnAndVersionHash(src.getSrcSn(), newHash);
        if (existingSameHash.isPresent()) {
            LsLabelVersion existing = existingSameHash.get();
            activeVersions.forEach(LsLabelVersion::deactivate);
            existing.activate();
            log.info("[Version] rollback reactivated existing srcSn={} hash={} version={} actor={}",
                    src.getSrcSn(), newHash, existing.getVersionNo(), actor.sub());
            return existing;
        }

        LsLabelVersion saved = saveActiveVersion(src, raw, activeVersions, newHash, snapshot,
                LsLabelVersion.SAVE_REASON_ROLLBACK, actor.sub());
        log.info("[Version] rolled back srcSn={} newHash={} version={} actor={}",
                src.getSrcSn(), newHash, saved.getVersionNo(), actor.sub());
        return saved;
    }

    /**
     * 스냅샷 페이로드(LabelResponse 직렬화 JSON)의 {@code items[]} 를 복원 라벨 목록으로 역직렬화한다.
     *
     * <p>스냅샷 형식(commitApproved 가 생성): {@code {srcSn, frameNo, ..., items:[{id, lblTypeCd,
     * label, labelId, points, ...}]}}. 각 item 에서 복원에 필요한 lblTypeCd / labelId / label /
     * points 만 추출한다(lbl_sn 은 재발급하므로 보존하지 않음).
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
            // SKELETON 은 삼중값 [[x,y,v],x17] 이라 2-튜플 readSnapshotPoints 로는 v 가 유실된다.
            // 롤백 round-trip 무손실을 위해 원본 points 노드 JSON 을 그대로 보존한다(type-route).
            String rawPointsJson = LsDataLbl.TYPE_SKELETON.equals(lblTypeCd)
                    ? item.path("points").toString() : null;
            result.add(new RestoredLabel(lblTypeCd, labelId, label,
                    readSnapshotPoints(item.path("points")), rawPointsJson));
        }
        return result;
    }

    /**
     * 프레임(src)의 기존 LS_DATA_LBL 을 모두 삭제하고 스냅샷 라벨을 재생성한다(lbl_sn 재발급).
     *
     * <p>같은 롤백 트랜잭션에 묶여 삭제+재생성이 원자적으로 적용된다. 라벨 0건 스냅샷이면 삭제만 수행해
     * 프레임 라벨을 비운다(빈 상태로의 롤백).
     */
    private void replaceFrameLabels(LsDataSrc src, List<RestoredLabel> restored) {
        Long srcSn = src.getSrcSn();
        List<LsDataLbl> existing = labelRepository.findBySrcSn(srcSn);
        if (!existing.isEmpty()) {
            labelRepository.deleteAll(existing);
            // delete 가 flush 되어 동일 트랜잭션 내 후속 insert 와 분리되도록 보장(IDENTITY PK 안전).
            labelRepository.flush();
        }
        List<LsDataLbl> toCreate = new ArrayList<>(restored.size());
        for (RestoredLabel r : restored) {
            String lblTypeCd = (r.lblTypeCd() == null || r.lblTypeCd().isBlank())
                    ? LsDataLbl.TYPE_BBOX : r.lblTypeCd();
            String label = (r.label() == null || r.label().isBlank()) ? "label" : r.label();
            // SKELETON 은 스냅샷의 원본 삼중값 JSON 을 그대로 복원(v 보존). 그 외는 기존 2-튜플 재직렬화(불변).
            String pointsJson = (LsDataLbl.TYPE_SKELETON.equals(lblTypeCd) && r.rawPointsJson() != null)
                    ? r.rawPointsJson()
                    : LabelPointSerializer.toJson(r.points(), objectMapper);
            toCreate.add(LsDataLbl.createManual(
                    srcSn, lblTypeCd, r.labelId(), label, pointsJson, null));
        }
        // N+1 INSERT 회피 — 낱건 save 대신 일괄 saveAll.
        if (!toCreate.isEmpty()) {
            labelRepository.saveAll(toCreate);
        }
        log.info("[Version] rollback restored labels srcSn={} deleted={} created={}",
                srcSn, existing.size(), restored.size());
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
     * 롤백 시 스냅샷에서 복원할 라벨 1건 — 복원에 필요한 필드만(lbl_sn 은 재발급). 외부 노출 없음.
     *
     * @param rawPointsJson SKELETON 삼중값 원본 points JSON(v 보존용). 2-튜플 타입은 null.
     */
    private record RestoredLabel(String lblTypeCd, Long labelId, String label,
                                 List<Point> points, String rawPointsJson) {
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
     * R7-1 — 비식별 신고 전용 스냅샷 직렬화.
     *
     * <p>영상 전체 라벨을 JSON 으로 직렬화하되, 일반 1MB 한도를 넘으면 폴리곤을 단순화하여 재직렬화한다.
     * 단순화 후에도 {@link #MAX_DEIDENT_PAYLOAD_BYTES}(10MB) 를 넘으면 예외(롤백) — 단, 라벨당 좌표 상한이
     * 상류에서 이미 적용되므로 정상 데이터에서는 도달하지 않는다. 신고는 안전장치이므로 일반 한도로
     * 차단하지 않고 단순화 + 상향 한도로 반드시 성공시키는 것이 정책이다.
     */
    private String serializeDeidentSnapshot(Long rawSn, List<LsDataLbl> labels) {
        // LabelResponse 직렬화 중복 제거 — 1회 생성 후 재활용.
        LabelResponse response = LabelResponse.of(labels, objectMapper);
        return serializeSnapshotWithSimplification(response, rawSn);
    }

    /**
     * BE-4 / R7-1 공통 — 라벨 스냅샷을 직렬화하되 1MB(일반 한도) 초과 시 폴리곤을 단순화한 뒤 상향
     * 한도({@link #MAX_DEIDENT_PAYLOAD_BYTES} 10MB)를 적용한다.
     *
     * <p>검수 승인 스냅샷(commitApproved)과 비식별 신고 스냅샷(snapshotDeidentReport) 모두 이 경로를
     * 사용한다. 라벨/폴리곤이 많은 영상에서 1MB 하드 한도로 정상 승인/신고가 차단되지 않도록,
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
                src.frameImageType(), src.lockSttsCd(), src.siblings(), items);
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
