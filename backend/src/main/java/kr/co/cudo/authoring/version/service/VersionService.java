package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Phase 5 — 라벨 버전관리 (DB 스냅샷 기반).
 *
 * <p>운영 제약상 외부 버전관리 서버를 둘 수 없어 라벨 버전/이력을 DB({@link LsLabelVersion}) 에만 저장한다.
 * 각 버전은 라벨 전체 JSON 스냅샷({@code LABEL_PAYLOAD}) 과 그 SHA-256({@code VERSION_HASH}) 을 보관하며,
 * diff/rollback 은 모두 이 스냅샷을 BE 에서 직접 파싱·비교하여 계산한다.
 *
 * <p>보안:
 * <ul>
 *   <li>IDOR (CWE-639): commit/list/diff/rollback 모두 {@link LabelAccessGuard} 로 srcSn 소유/배정 검증.</li>
 *   <li>Race (CWE-362): 같은 srcSn 동시 commit/rollback 시 ACTIVE 행 비관적 잠금으로 직렬화.</li>
 *   <li>CWE-770 DoS: 스냅샷 페이로드 1MB 한도 검증.</li>
 *   <li>Privacy (CWE-359): 라벨 본문은 로그 출력 금지.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VersionService {

    /** CWE-770 — 단일 라벨 스냅샷 페이로드 최대 크기 (바이트). */
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private final LsLabelVersionRepository labelVersionRepository;
    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final ObjectMapper objectMapper;

    /**
     * 현재 라벨 상태를 새 버전으로 저장한다.
     *
     * <p>접근권한 + 잠금 검증 후 라벨 JSON 의 SHA-256 을 계산하고, 스냅샷과 함께 새 active 버전을 기록한다.
     * 멱등: 같은 프레임의 현재 active 버전과 동일한 페이로드(=동일 versionHash) 면 새 row 를 만들지 않고
     * 기존 active 버전의 해시를 그대로 반환한다 (저장 버튼 더블클릭/재시도 안전, 복합 UNIQUE 충돌 회피).
     *
     * @return 저장된(또는 기존) 버전의 versionHash
     */
    @Transactional("controlTransactionManager")
    public String commit(Long srcSn, String labelsJson, TokenClaims actor) {
        if (srcSn == null) {
            throw new IllegalArgumentException("srcSn 은 필수입니다.");
        }
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        LsDataRaw raw = videoRepository.findById(src.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (workLockService.isRawLocked(raw.getRawSn())) {
            throw new CustomException(ErrorCode.CONFLICT, "비식별 재처리 중인 영상은 라벨을 수정할 수 없습니다.");
        }

        String payload = labelsJson == null ? "" : labelsJson;
        validatePayloadSize(payload);
        String versionHash = sha256Hex(payload);

        // Race 직렬화 — ACTIVE 행 비관적 잠금. 동시 commit/rollback 트랜잭션이 순차 진행되어
        // versionNo 충돌·active 중복을 차단한다.
        List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
                raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);

        // 멱등 재커밋 — 현재 active 가 동일 스냅샷이면 새 버전 생성하지 않음.
        for (LsLabelVersion active : activeVersions) {
            if (versionHash.equals(active.getVersionHash())) {
                log.info("[Version] commit idempotent srcSn={} hash={} actor={}",
                        srcSn, versionHash, actor.sub());
                return versionHash;
            }
        }

        LsLabelVersion saved = saveActiveVersion(src, raw, activeVersions, versionHash, payload,
                LsLabelVersion.SAVE_REASON_MANUAL, actor.sub());
        log.info("[Version] committed srcSn={} hash={} version={} actor={}",
                srcSn, versionHash, saved.getVersionNo(), actor.sub());
        return versionHash;
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
     * <p>각 버전의 {@code LABEL_PAYLOAD} 를 파싱하여 라벨 단위 ADDED/REMOVED/MODIFIED 를 계산한다.
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
     * <p>대상 버전의 {@code LABEL_PAYLOAD} 를 그대로 새 active 버전(saveReason=ROLLBACK)으로 기록한다.
     * 접근권한 + ACTIVE 비관적 잠금으로 동시성/IDOR 방어.
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

        List<LsLabelVersion> activeVersions = labelVersionRepository.findActiveForUpdate(
                raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);

        String snapshot = target.getLabelPayload() == null ? "" : target.getLabelPayload();
        // 롤백 결과 스냅샷의 해시 — 같은 프레임 내 멱등성을 위해 재계산.
        String newHash = sha256Hex(snapshot);

        // 현재 active 가 이미 롤백 대상과 동일 스냅샷이면 멱등 — 기존 active 반환.
        for (LsLabelVersion active : activeVersions) {
            if (newHash.equals(active.getVersionHash())) {
                log.info("[Version] rollback idempotent srcSn={} hash={} actor={}",
                        srcSn, newHash, actor.sub());
                return active;
            }
        }

        LsLabelVersion saved = saveActiveVersion(src, raw, activeVersions, newHash, snapshot,
                LsLabelVersion.SAVE_REASON_ROLLBACK, actor.sub());
        log.info("[Version] rolled back srcSn={} toHash={} newHash={} version={} actor={}",
                srcSn, versionHash, newHash, saved.getVersionNo(), actor.sub());
        return saved;
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

    private void validatePayloadSize(String payload) {
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_PAYLOAD_BYTES) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨 스냅샷 크기 초과 (최대 " + MAX_PAYLOAD_BYTES + " 바이트)");
        }
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
            List<List<Double>> points = readPoints(item.path("points"));
            result.put(id, new LabelSnapshot(id, lblTypeCd, label, points, frameNo));
        }
        return result;
    }

    private static List<List<Double>> readPoints(JsonNode pointsNode) {
        if (!pointsNode.isArray()) {
            return List.of();
        }
        List<List<Double>> out = new ArrayList<>(pointsNode.size());
        for (JsonNode pair : pointsNode) {
            if (pair.isArray() && pair.size() >= 2
                    && pair.get(0).isNumber() && pair.get(1).isNumber()) {
                out.add(List.of(pair.get(0).asDouble(), pair.get(1).asDouble()));
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
