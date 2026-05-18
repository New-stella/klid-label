package kr.co.cudo.authoring.version.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
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
import java.util.List;

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
        String message = "label update by " + actor.sub();
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

        DiffResponse resp = giteaClient.diff(repo, fromSha, toSha)
                .block(GiteaClient.BLOCK_TIMEOUT);
        if (resp == null) {
            return new DiffResponseDto(fromSha, toSha, List.of());
        }
        return DiffResponseDto.of(fromSha, toSha, resp);
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
