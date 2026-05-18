package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffFile;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VersionService {

    private final LsLabelVersionRepository labelVersionRepository;
    private final GiteaClient giteaClient;
    private final GiteaPathPolicy pathPolicy;
    private final GiteaCommitFallbackQueue fallbackQueue;
    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final WorkLockService workLockService;
    private final ObjectMapper objectMapper;

    @Value("${authoring.integration.gitea.repo}")
    private String repo;

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

        String path = pathPolicy.path(srcSn);
        String message = buildCommitMessage(path, labelsJson, src.getFrameNo(), srcSn, actor.sub());
        String contentBase64 = Base64.getEncoder().encodeToString(
                (labelsJson == null ? "" : labelsJson).getBytes(StandardCharsets.UTF_8));
        try {
            CommitResponse resp = giteaClient
                    .createOrUpdateFile(repo, path, contentBase64, message, actor.sub(), "main")
                    .block(GiteaClient.BLOCK_TIMEOUT);
            String sha = resp == null ? "" : resp.sha();
            saveActiveVersion(src, raw, sha, LsLabelVersion.SAVE_REASON_MANUAL, actor.sub());
            log.info("[Version] committed srcSn={} sha={} actor={}", srcSn, sha, actor.sub());
            return sha;
        } catch (Exception e) {
            log.warn("[Version] gitea commit failed srcSn={} actor={} reason={}",
                    srcSn, actor.sub(), e.getClass().getSimpleName());
            fallbackQueue.enqueue(srcSn, labelsJson, actor.sub());
            return null;
        }
    }

    public List<VersionItem> listVersions(Long srcSn, TokenClaims actor) {
        accessGuard.verifyAccess(srcSn, actor);
        List<LsLabelVersion> versions = labelVersionRepository.findByDataSrcSnOrderByRegDtDesc(srcSn);
        if (versions.isEmpty()) {
            return Collections.emptyList();
        }

        List<CommitResponse> giteaCommits = Collections.emptyList();
        try {
            List<CommitResponse> resp = giteaClient.listCommits(repo, pathPolicy.path(srcSn), 100)
                    .block(GiteaClient.BLOCK_TIMEOUT);
            if (resp != null) {
                giteaCommits = resp;
            }
        } catch (Exception e) {
            log.warn("[Version] gitea listCommits failed - fallback to LS_LABEL_VERSION. srcSn={} reason={}",
                    srcSn, e.getClass().getSimpleName());
        }

        if (!giteaCommits.isEmpty()) {
            List<VersionItem> items = new ArrayList<>(giteaCommits.size());
            for (int i = 0; i < giteaCommits.size(); i++) {
                items.add(VersionItem.fromGitea(giteaCommits.get(i), i == 0));
            }
            return items;
        }
        List<VersionItem> items = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            items.add(VersionItem.fromLabelVersion(versions.get(i), i == 0));
        }
        return items;
    }

    public DiffResponseDto diff(String fromSha, String toSha, TokenClaims actor) {
        validateSha(fromSha);
        validateSha(toSha);

        LsLabelVersion fromVersion = labelVersionRepository.findByGiteaCmtHash(fromSha)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "from 커밋을 찾을 수 없습니다."));
        LsLabelVersion toVersion = labelVersionRepository.findByGiteaCmtHash(toSha)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "to 커밋을 찾을 수 없습니다."));
        accessGuard.verifyAccess(fromVersion.getDataSrcSn(), actor);
        accessGuard.verifyAccess(toVersion.getDataSrcSn(), actor);

        // 1차: Gitea compare API 사용 — files 필드가 응답에 포함되면 그대로 사용
        DiffResponse resp = null;
        try {
            resp = giteaClient.diff(repo, fromSha, toSha).block(GiteaClient.BLOCK_TIMEOUT);
        } catch (Exception e) {
            log.warn("[Version] gitea compare failed - fallback to raw content diff. from={} to={} reason={}",
                    fromSha, toSha, e.getClass().getSimpleName());
        }

        // 라벨 단위 diff 산출은 항상 시도 — 같은 srcSn 일 때만 의미 있음.
        // FE 작업이력 패널이 사용하는 핵심 필드이므로 compare 결과와 무관하게 항상 채운다.
        List<LabelDiffDto> labelDiffs = computeLabelDiffsIfSameSrc(fromVersion, toVersion, fromSha, toSha);

        if (resp != null && resp.files() != null && !resp.files().isEmpty()) {
            return DiffResponseDto.of(fromSha, toSha, resp, labelDiffs);
        }

        // 2차 fallback: Gitea compare 응답에 files 가 없으므로 (Gitea compare API 사양 — commits 만 반환),
        // 두 SHA 시점의 라벨 파일을 직접 받아 BE 에서 내용 비교한다.
        // srcSn 당 라벨 파일은 1개 (labels/{srcSn}.json) — fromVersion.dataSrcSn 과 toVersion.dataSrcSn 이
        // 같을 때만 의미 있는 비교가 가능하므로 다른 경우는 빈 결과를 반환한다.
        if (!fromVersion.getDataSrcSn().equals(toVersion.getDataSrcSn())) {
            return new DiffResponseDto(fromSha, toSha, List.of(), List.of());
        }
        String path = pathPolicy.path(fromVersion.getDataSrcSn());
        String fromContent = fetchContentOrEmpty(fromSha, path);
        String toContent = fetchContentOrEmpty(toSha, path);
        if (fromContent.equals(toContent)) {
            // 동일 내용 → 변경 없음
            return new DiffResponseDto(fromSha, toSha, List.of(), List.of());
        }
        DiffFile file = buildDiffFile(path, fromContent, toContent);
        // raw content diff 경로에서는 위 computeLabelDiffsIfSameSrc 가 이미 같은 두 SHA 의 content 로 비교했으므로 그대로 사용.
        return DiffResponseDto.of(fromSha, toSha, new DiffResponse(List.of(file)), labelDiffs);
    }

    /**
     * commit 메시지 enrichment — 현재 HEAD content 와 새 labelsJson 을 비교해
     * 프레임 번호 + 추가/삭제/수정 카운트 + 작성자를 포함한 한글 메시지를 생성한다.
     *
     * <p>형식:
     * <ul>
     *   <li>변화 있음: {@code "프레임 N: +A개 추가, -R개 삭제, ~M개 수정 (작성자: ACTOR)"} (카운트 0 항목은 생략)</li>
     *   <li>최초 commit (HEAD 없음): {@code "프레임 N: +A개 추가 (작성자: ACTOR)"} 또는 신규 라벨도 없으면 {@code "프레임 N: 최초 커밋 (작성자: ACTOR)"}</li>
     *   <li>변화 없음 (re-commit): {@code "프레임 N: 변경 없음 (재커밋) (작성자: ACTOR)"}</li>
     *   <li>fetch/parse 실패: 기본 메시지 {@code "라벨 저장 (작성자: ACTOR)"} 로 폴백 (회귀가드)</li>
     * </ul>
     *
     * <p>frameNo 가 null 이면 {@code "프레임 ?"} 으로 표기 (srcSn 기반 폴백은 frameNo 보장이 강해 사용하지 않음).
     */
    private String buildCommitMessage(String path, String newLabelsJson, Integer frameNo,
                                      Long srcSn, String actorSub) {
        String defaultMessage = "라벨 저장 (작성자: " + actorSub + ")";
        String frameLabel = "프레임 " + (frameNo != null ? frameNo : "?");
        try {
            // HEAD content fetch — 최초 commit 이면 404 등으로 실패 → 빈 문자열 처리
            String headContent = fetchHeadContentOrEmpty(path);
            Map<String, LabelSnapshot> beforeMap = parseLabelsById(headContent);
            Map<String, LabelSnapshot> afterMap = parseLabelsById(newLabelsJson);

            int added = 0;
            int modified = 0;
            int removed = 0;
            for (Map.Entry<String, LabelSnapshot> e : afterMap.entrySet()) {
                LabelSnapshot before = beforeMap.get(e.getKey());
                if (before == null) {
                    added++;
                } else if (!before.equalsContent(e.getValue())) {
                    modified++;
                }
            }
            for (String id : beforeMap.keySet()) {
                if (!afterMap.containsKey(id)) {
                    removed++;
                }
            }

            boolean initialCommit = headContent.isEmpty();
            if (added == 0 && removed == 0 && modified == 0) {
                if (initialCommit) {
                    return frameLabel + ": 최초 커밋 (작성자: " + actorSub + ")";
                }
                return frameLabel + ": 변경 없음 (재커밋) (작성자: " + actorSub + ")";
            }
            StringBuilder sb = new StringBuilder(frameLabel).append(": ");
            boolean first = true;
            if (added > 0) {
                sb.append("+").append(added).append("개 추가");
                first = false;
            }
            if (removed > 0) {
                if (!first) sb.append(", ");
                sb.append("-").append(removed).append("개 삭제");
                first = false;
            }
            if (modified > 0) {
                if (!first) sb.append(", ");
                sb.append("~").append(modified).append("개 수정");
            }
            sb.append(" (작성자: ").append(actorSub).append(")");
            return sb.toString();
        } catch (Exception e) {
            log.warn("[Version] commit message enrichment failed srcSn={} reason={} - fallback to default message",
                    srcSn, e.getClass().getSimpleName());
            return defaultMessage;
        }
    }

    /**
     * 현재 HEAD 시점의 라벨 파일 content. 최초 commit 이거나 fetch 실패 시 빈 문자열.
     * {@link #fetchContentOrEmpty(String, String)} 와 분리한 이유: HEAD 는 ref="HEAD" 로 조회.
     */
    private String fetchHeadContentOrEmpty(String path) {
        try {
            String content = giteaClient.getContent(repo, "HEAD", path).block(GiteaClient.BLOCK_TIMEOUT);
            return content == null ? "" : content;
        } catch (Exception e) {
            // 최초 commit (파일 없음) 도 여기로 떨어짐 — 정상 케이스로 빈 문자열 반환.
            log.debug("[Version] HEAD content fetch returned empty path={} reason={}",
                    path, e.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 두 SHA 시점의 라벨 JSON 을 받아 라벨 단위 diff 산출.
     * 라벨 식별자: LS_DATA_LBL.LBL_SN (응답 snapshot 의 {@code items[].id}).
     *
     * <p>분류 기준:
     * <ul>
     *   <li>fromSha 에 없고 toSha 에 있음 → {@link LabelDiffDto.DiffType#ADDED}</li>
     *   <li>fromSha 에 있고 toSha 에 없음 → {@link LabelDiffDto.DiffType#REMOVED}</li>
     *   <li>양쪽 모두 존재 + 좌표/lblTypeCd/label 중 하나라도 다름 → {@link LabelDiffDto.DiffType#MODIFIED}</li>
     * </ul>
     *
     * <p>같은 srcSn(=프레임)이 아니면 의미 있는 라벨 비교가 불가능하므로 빈 리스트 반환.
     * JSON 파싱 실패도 빈 리스트 반환 (장애 격리 — 파일 단위 diff 는 계속 제공).
     */
    private List<LabelDiffDto> computeLabelDiffsIfSameSrc(LsLabelVersion fromVersion,
                                                          LsLabelVersion toVersion,
                                                          String fromSha,
                                                          String toSha) {
        if (!Objects.equals(fromVersion.getDataSrcSn(), toVersion.getDataSrcSn())) {
            return List.of();
        }
        String path = pathPolicy.path(fromVersion.getDataSrcSn());
        String fromContent = fetchContentOrEmpty(fromSha, path);
        String toContent = fetchContentOrEmpty(toSha, path);
        if (fromContent.isEmpty() && toContent.isEmpty()) {
            return List.of();
        }
        try {
            Map<String, LabelSnapshot> fromMap = parseLabelsById(fromContent);
            Map<String, LabelSnapshot> toMap = parseLabelsById(toContent);
            return diffLabelMaps(fromMap, toMap);
        } catch (Exception e) {
            log.warn("[Version] label diff parse failed from={} to={} reason={}",
                    fromSha, toSha, e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 라벨 응답 snapshot JSON ({@code {srcSn, frameNo, items:[{id, lblTypeCd, label, points, ...}]}}) 을
     * id → LabelSnapshot 맵으로 변환. id 가 null/누락이면 skip (anonymous 신규 라벨 — commit 시점에 id 부여 전).
     */
    private Map<String, LabelSnapshot> parseLabelsById(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            // JSON 파싱 실패는 호출부 try/catch 에서 흡수.
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
        // REMOVED + MODIFIED 분류
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
        // ADDED 분류
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

    private String fetchContentOrEmpty(String sha, String path) {
        try {
            String content = giteaClient.getContent(repo, sha, path).block(GiteaClient.BLOCK_TIMEOUT);
            return content == null ? "" : content;
        } catch (Exception e) {
            log.warn("[Version] gitea getContent failed sha={} reason={}", sha, e.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 두 라벨 JSON 내용에 대한 단순 라인-기반 diff 산출.
     * change: from 비어있으면 "added" / to 비어있으면 "removed" / 둘 다 있으면 "modified".
     * additions/deletions: 다른 라인 개수 — to 에만 있는 라인은 additions, from 에만 있는 라인은 deletions.
     * patch: null (FE 에서 별도 fetch). DiffResponseDto 의 길이 제한 통과.
     */
    private static DiffFile buildDiffFile(String path, String fromContent, String toContent) {
        String change;
        if (fromContent.isEmpty()) {
            change = "added";
        } else if (toContent.isEmpty()) {
            change = "removed";
        } else {
            change = "modified";
        }
        java.util.Set<String> fromLines = new java.util.HashSet<>(java.util.Arrays.asList(fromContent.split("\n")));
        java.util.Set<String> toLines = new java.util.HashSet<>(java.util.Arrays.asList(toContent.split("\n")));
        int additions = 0;
        for (String l : toLines) {
            if (!fromLines.contains(l)) additions++;
        }
        int deletions = 0;
        for (String l : fromLines) {
            if (!toLines.contains(l)) deletions++;
        }
        return new DiffFile(path, change, additions, deletions, null);
    }

    @Transactional("controlTransactionManager")
    public LsLabelVersion rollback(String commitHash, Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // 인가는 accessGuard.verifyAndGet 에 위임:
        // REVIEWER 는 통과 / WORKER 는 본인 LABELER 배정 영상만 통과 (CWE-639 IDOR 방어)
        validateSha(commitHash);
        accessGuard.verifyAccess(srcSn, actor);

        LsLabelVersion target = labelVersionRepository.findByGiteaCmtHash(commitHash)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "롤백 대상 커밋을 찾을 수 없습니다."));
        if (!target.getDataSrcSn().equals(srcSn)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "커밋 해시와 프레임이 일치하지 않습니다.");
        }

        String snapshot = null;
        try {
            snapshot = giteaClient.getContent(repo, commitHash, pathPolicy.path(srcSn))
                    .block(GiteaClient.BLOCK_TIMEOUT);
        } catch (Exception e) {
            log.warn("[Version] rollback content fetch failed srcSn={} sha={} reason={}",
                    srcSn, commitHash, e.getClass().getSimpleName());
        }

        String newSha = null;
        try {
            String contentBase64 = Base64.getEncoder().encodeToString(
                    (snapshot == null ? "" : snapshot).getBytes(StandardCharsets.UTF_8));
            CommitResponse resp = giteaClient
                    .createOrUpdateFile(repo, pathPolicy.path(srcSn), contentBase64,
                            "rollback to " + commitHash + " by " + actor.sub(), actor.sub(), "main")
                    .block(GiteaClient.BLOCK_TIMEOUT);
            newSha = resp == null ? null : resp.sha();
        } catch (Exception e) {
            log.warn("[Version] rollback commit failed - enqueue retry srcSn={} reason={}",
                    srcSn, e.getClass().getSimpleName());
            fallbackQueue.enqueue(srcSn, snapshot, actor.sub());
        }

        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        LsDataRaw raw = videoRepository.findById(src.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return saveActiveVersion(src, raw, newSha == null ? commitHash : newSha,
                LsLabelVersion.SAVE_REASON_ROLLBACK, actor.sub());
    }

    private LsLabelVersion saveActiveVersion(LsDataSrc src, LsDataRaw raw, String sha,
                                             String reasonCd, String actorId) {
        Long pjtSn = 0L;
        List<LsLabelVersion> activeVersions = labelVersionRepository
                .findByPjtSnAndDataRawSnAndDataSrcSnAndActiveYn(
                        pjtSn, raw.getRawSn(), src.getSrcSn(), LsLabelVersion.ACTIVE_YES);
        activeVersions.forEach(LsLabelVersion::deactivate);
        int nextVersion = labelVersionRepository.countByPjtSnAndDataRawSnAndDataSrcSn(
                pjtSn, raw.getRawSn(), src.getSrcSn()) + 1;
        return labelVersionRepository.save(LsLabelVersion.create(
                pjtSn, raw.getRawSn(), src.getSrcSn(), sha, nextVersion, reasonCd, actorId));
    }

    private static void validateSha(String sha) {
        if (sha == null || sha.isEmpty() || sha.length() > 64) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 커밋 해시 형식입니다.");
        }
        for (int i = 0; i < sha.length(); i++) {
            char c = sha.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "잘못된 커밋 해시 형식입니다.");
            }
        }
    }

    public static boolean isCommittable(TokenClaims actor) {
        return actor != null && actor.channel() != Channel.PORTAL;
    }
}
