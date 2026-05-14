package kr.co.cudo.authoring.version.service;

import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
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

/**
 * Phase 8 — Gitea 버전관리 서비스.
 *
 * <p>주요 책임:
 * <ul>
 *   <li>{@link #commit} : 라벨 저장 시 Gitea 자동 커밋 + LS_DATA_LBL_HSTRY 기록.
 *       장애 시 fallback 큐 등록 (라벨 저장 자체는 성공 — Mishandling of Exceptional Conditions A10:2025).</li>
 *   <li>{@link #listVersions} : 프레임 단위 버전 이력 조회.</li>
 *   <li>{@link #diff} : 두 커밋 비교.</li>
 *   <li>{@link #rollback} : REVIEWER 만 — 과거 커밋 시점으로 롤백 (LabelAccessGuard 로 IDOR 방어).</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li>RBAC : commit 은 WORKER/REVIEWER 모두 / rollback 은 REVIEWER 만.</li>
 *   <li>Channel : PORTAL 채널은 commit 호출 자체를 skip (포털은 버전관리 미제공) — LabelService 가 사전 분기.</li>
 *   <li>IDOR (CWE-639) : rollback 은 LabelAccessGuard.verifyAccess(srcSn, actor) 재사용.</li>
 *   <li>SSRF (CWE-918) : Gitea base-url 은 application.yml 에서 주입 (사용자 입력 X).</li>
 *   <li>Path Manipulation (CWE-22) : path 는 {@link GiteaPathPolicy} 가 srcSn(Long) 으로만 생성.</li>
 *   <li>Privacy (CWE-359) : labels JSON 본문 / GITEA_TOKEN 은 로그 출력 금지 (해시/길이만).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VersionService {

    private final LsDataLblHstryRepository historyRepository;
    private final GiteaClient giteaClient;
    private final GiteaPathPolicy pathPolicy;
    private final GiteaCommitFallbackQueue fallbackQueue;
    private final LabelAccessGuard accessGuard;

    @Value("${authoring.integration.gitea.repo}")
    private String repo;

    /**
     * 라벨 변경 시 Gitea 자동 커밋.
     *
     * <p>성공: LS_DATA_LBL_HSTRY INSERT (GITEA_CMT_HASH 채워짐) + commit SHA 반환.
     * <p>실패: fallback 큐에 enqueue + null 반환 (라벨 저장 자체는 별도 트랜잭션에서 이미 성공).
     *
     * @return 성공 시 커밋 SHA, 실패 시 null
     */
    @Transactional("controlTransactionManager")
    public String commit(Long srcSn, String labelsJson, TokenClaims actor) {
        if (srcSn == null) {
            throw new IllegalArgumentException("srcSn 은 필수입니다.");
        }
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
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
            historyRepository.save(LsDataLblHstry.create(srcSn, sha, actor.sub(), labelsJson));
            log.info("[Version] committed srcSn={} sha={} actor={}", srcSn, sha, actor.sub());
            return sha;
        } catch (Exception e) {
            // CWE-209/CWE-359: 예외 메시지에 내부 경로/토큰 노출 금지 — 메시지 / 스택트레이스만.
            log.warn("[Version] gitea commit failed srcSn={} actor={} reason={}",
                    srcSn, actor.sub(), e.getClass().getSimpleName());
            // 라벨 본문은 history 테이블 + fallback 큐에서만 보관 (재시도 위해).
            historyRepository.save(LsDataLblHstry.createPending(srcSn, actor.sub(), labelsJson));
            fallbackQueue.enqueue(srcSn, labelsJson, actor.sub());
            return null;
        }
    }

    /**
     * 프레임 단위 버전 목록 — 최신순.
     *
     * <p>응답 정책 (FE Version[] 정합):
     * <ul>
     *   <li>새로 등록된 영상이라 commit 이 0건이면 → 200 + 빈 배열.</li>
     *   <li>Gitea 호출 성공: Gitea commit 메타(작가/메시지/일시) 우선 사용.</li>
     *   <li>Gitea 호출 실패(404/네트워크/timeout): WARN 로그 + DB({@link LsDataLblHstry}) 단독 fallback.
     *       Gitea 일시 장애나 신규 영상(path 없음)이 사용자에게 500 으로 보이지 않도록 한다
     *       (OWASP A10:2025 — Mishandling of Exceptional Conditions).</li>
     * </ul>
     */
    public List<VersionItem> listVersions(Long srcSn, TokenClaims actor) {
        accessGuard.verifyAccess(srcSn, actor);
        List<LsDataLblHstry> history = historyRepository.findBySrcSnOrderByRegisteredAtDesc(srcSn);
        if (history.isEmpty()) {
            // 신규 영상 — 아직 한 번도 커밋되지 않음. Gitea 호출 자체를 skip.
            return Collections.emptyList();
        }
        String path = pathPolicy.path(srcSn);

        List<CommitResponse> giteaCommits = Collections.emptyList();
        try {
            List<CommitResponse> resp = giteaClient.listCommits(repo, path, 100)
                    .block(GiteaClient.BLOCK_TIMEOUT);
            if (resp != null) {
                giteaCommits = resp;
            }
        } catch (Exception e) {
            // Gitea 404 (신규 path) / timeout / 네트워크 단절 — empty list 로 graceful fallback.
            // CWE-209/CWE-359: 예외 원인은 클래스명만 (스택트레이스/내부 경로 노출 금지).
            log.warn("[Version] gitea listCommits failed — fallback to DB only. srcSn={} reason={}",
                    srcSn, e.getClass().getSimpleName());
        }

        // 1) Gitea 응답이 있으면 hash 매핑 후 enrich.
        //    DB 에 hash 가 있는 history 만 매핑 (PENDING(hash null) 은 Gitea 응답에 존재할 수 없음).
        // 2) Gitea 응답이 비어 있으면 DB 단독 — PENDING 도 포함하여 사용자에게 "기록은 남았음" 을 노출.
        if (!giteaCommits.isEmpty()) {
            List<VersionItem> items = new ArrayList<>(giteaCommits.size());
            for (int i = 0; i < giteaCommits.size(); i++) {
                items.add(VersionItem.fromGitea(giteaCommits.get(i), i == 0));
            }
            return items;
        }
        List<VersionItem> items = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            items.add(VersionItem.fromHistory(history.get(i), i == 0));
        }
        return items;
    }

    /**
     * 두 커밋 비교. 커밋 해시 1건만 받아서 (compareWith 가 null 이면 직전 버전과 비교).
     * 호출 권한: WORKER/REVIEWER (commit 한 본인이 자기 영상의 이력을 보는 케이스 고려 — accessGuard 재사용).
     */
    public DiffResponseDto diff(String fromSha, String toSha, TokenClaims actor) {
        if (fromSha == null || fromSha.isBlank() || toSha == null || toSha.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비교 대상 커밋 해시가 필요합니다.");
        }
        // SHA 형식 1차 검증 (Path Manipulation/Header Injection 추가 차단)
        validateSha(fromSha);
        validateSha(toSha);

        // 권한: 두 커밋이 어느 srcSn 인지 확인 후 accessGuard
        LsDataLblHstry fromHist = historyRepository.findByGiteaCmtHash(fromSha)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "from 커밋을 찾을 수 없습니다."));
        LsDataLblHstry toHist = historyRepository.findByGiteaCmtHash(toSha)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "to 커밋을 찾을 수 없습니다."));
        accessGuard.verifyAccess(fromHist.getSrcSn(), actor);
        accessGuard.verifyAccess(toHist.getSrcSn(), actor);

        DiffResponse resp = giteaClient.diff(repo, fromSha, toSha)
                .block(GiteaClient.BLOCK_TIMEOUT);
        if (resp == null) {
            return new DiffResponseDto(fromSha, toSha, List.of());
        }
        return DiffResponseDto.of(fromSha, toSha, resp);
    }

    /**
     * 과거 커밋 시점으로 라벨 롤백 — REVIEWER 만 가능.
     *
     * <p>현재 구현: 해당 커밋 해시의 history 메타를 확인 + 새 history row INSERT (롤백 마킹).
     * 실제 라벨 row 의 좌표 복원은 LabelService 에서 별도 호출 (본 메서드는 메타 + 기록 책임만).
     *
     * @return 새로 생성된 history (롤백 마커)
     */
    @Transactional("controlTransactionManager")
    public LsDataLblHstry rollback(String commitHash, Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "REVIEWER 권한이 필요합니다.");
        }
        if (commitHash == null || commitHash.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "커밋 해시가 필요합니다.");
        }
        validateSha(commitHash);
        // IDOR 추가 방어 — srcSn 접근 권한 (REVIEWER 는 통과)
        accessGuard.verifyAccess(srcSn, actor);

        LsDataLblHstry target = historyRepository.findByGiteaCmtHash(commitHash)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "롤백 대상 커밋을 찾을 수 없습니다."));
        if (!target.getSrcSn().equals(srcSn)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "커밋 해시와 프레임이 일치하지 않습니다.");
        }

        // 과거 시점의 라벨 JSON 복원 (history 에 저장된 snapshot 우선 — Gitea 호출 실패해도 동작 가능)
        String snapshot = target.getLabelsJsonSnapshot();
        if (snapshot == null) {
            try {
                snapshot = giteaClient.getContent(repo, commitHash, pathPolicy.path(srcSn))
                        .block(GiteaClient.BLOCK_TIMEOUT);
            } catch (Exception e) {
                log.warn("[Version] rollback content fetch failed srcSn={} sha={} reason={}",
                        srcSn, commitHash, e.getClass().getSimpleName());
            }
        }

        // 롤백을 새 커밋으로 기록 (감사 추적)
        String message = "rollback to " + commitHash + " by " + actor.sub();
        String newSha = null;
        try {
            String contentBase64 = Base64.getEncoder().encodeToString(
                    (snapshot == null ? "" : snapshot).getBytes(StandardCharsets.UTF_8));
            CommitResponse resp = giteaClient
                    .createOrUpdateFile(repo, pathPolicy.path(srcSn), contentBase64, message,
                            actor.sub(), "main")
                    .block(GiteaClient.BLOCK_TIMEOUT);
            newSha = resp == null ? null : resp.sha();
        } catch (Exception e) {
            log.warn("[Version] rollback commit failed - enqueue retry srcSn={} reason={}",
                    srcSn, e.getClass().getSimpleName());
            fallbackQueue.enqueue(srcSn, snapshot, actor.sub());
        }
        LsDataLblHstry hist = (newSha != null)
                ? LsDataLblHstry.create(srcSn, newSha, actor.sub(), snapshot)
                : LsDataLblHstry.createPending(srcSn, actor.sub(), snapshot);
        return historyRepository.save(hist);
    }

    /** SHA 형식 검증 — 16진수 1~64자. 그 외 입력 거부 (CWE-22 / CWE-113). */
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

    /** 채널이 PORTAL 이면 버전관리 미제공 → commit skip 판정용. */
    public static boolean isCommittable(TokenClaims actor) {
        return actor != null && actor.channel() != Channel.PORTAL;
    }
}
