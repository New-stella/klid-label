package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.client.dto.DiffFile;
import kr.co.cudo.authoring.common.client.dto.DiffResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gitea REST API 클라이언트 (Phase 0 + Phase 8 확장).
 *
 * <p>외부 호출은 모두 {@link CircuitBreakerOperator} + {@link RetryOperator} + 60s timeout 적용.
 *
 * <p>보안:
 * <ul>
 *   <li>SSRF (CWE-918): base-url 은 application.yml 설정값 — 사용자 입력 X.</li>
 *   <li>Path Manipulation (CWE-22): owner/repo/path 는 호출자 책임 (GiteaPathPolicy 가 srcSn Long 으로 생성).</li>
 *   <li>Privacy (CWE-359): GITEA_TOKEN, labels JSON 본문은 로그 출력 금지.</li>
 * </ul>
 */
@Component
public class GiteaClient {

    /** Reactor 호출 자체 timeout (각 Mono 에 적용). */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    /**
     * 호출자가 .block() 사용 시 적용할 권장 timeout.
     * CALL_TIMEOUT(60s) + 응답 처리·디시리얼라이즈·circuit breaker 마진 10s = 70s.
     */
    public static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(70);

    private final WebClient webClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final String owner;

    public GiteaClient(@Qualifier("giteaWebClient") WebClient webClient,
                       @Qualifier("giteaCircuitBreaker") CircuitBreaker circuitBreaker,
                       RetryRegistry retryRegistry,
                       @Value("${authoring.integration.gitea.owner}") String owner) {
        this.webClient = webClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("gitea");
        this.owner = owner;
    }

    /**
     * 단순 커밋 — Phase 0 호환용 (label-svc 에서 사용 예정 시점에는 createOrUpdateFile 권장).
     * @return commit SHA (빈 문자열일 수 있음)
     */
    @SuppressWarnings("unchecked")
    public Mono<String> commit(String repo, String branch, String filePath, String message, byte[] content) {
        String contentBase64 = Base64.getEncoder().encodeToString(content);
        Map<String, Object> body = Map.of(
                "branch", branch,
                "message", message,
                "content", contentBase64
        );
        return webClient.post()
                .uri("/api/v1/repos/{owner}/{repo}/contents/{path}", owner, repo, filePath)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> {
                    Object commit = resp.get("commit");
                    if (commit instanceof Map<?, ?> m) {
                        Object sha = m.get("sha");
                        return sha == null ? "" : sha.toString();
                    }
                    return "";
                })
                .timeout(CALL_TIMEOUT)
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 파일 생성 또는 갱신.
     * Gitea PUT /api/v1/repos/{owner}/{repo}/contents/{path}.
     * 응답: {"commit": {"sha": "...", "message": "...", "author": {"name": "..."}}}
     *
     * @param repo            Gitea repo 이름
     * @param path            파일 경로 (예: "10/123/456.json")
     * @param contentBase64   base64 인코딩된 파일 내용
     * @param message         커밋 메시지
     * @param author          커밋 작성자 (USER_NO 등)
     * @param branch          브랜치 (예: "main")
     */
    @SuppressWarnings("unchecked")
    public Mono<CommitResponse> createOrUpdateFile(String repo, String path, String contentBase64,
                                                    String message, String author, String branch) {
        Map<String, Object> body = new HashMap<>();
        body.put("branch", branch);
        body.put("message", message);
        body.put("content", contentBase64);
        body.put("author", Map.of("name", author == null ? "" : author));
        return webClient.put()
                .uri("/api/v1/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> parseCommitFromContentsApi(resp, message, author))
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 단일 커밋 메타 조회.
     * Gitea GET /api/v1/repos/{owner}/{repo}/git/commits/{sha}.
     */
    @SuppressWarnings("unchecked")
    public Mono<CommitResponse> getCommit(String repo, String sha) {
        return webClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/git/commits/{sha}", owner, repo, sha)
                .retrieve()
                .bodyToMono(Map.class)
                .map(resp -> parseCommitFromGitCommitsApi(resp))
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 특정 파일 경로의 커밋 이력 — 최신순 N건.
     * Gitea GET /api/v1/repos/{owner}/{repo}/commits?path=&limit=
     */
    @SuppressWarnings("unchecked")
    public Mono<List<CommitResponse>> listCommits(String repo, String path, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/repos/{owner}/{repo}/commits")
                        .queryParam("path", path)
                        .queryParam("limit", safeLimit)
                        .build(owner, repo))
                .retrieve()
                .bodyToMono(List.class)
                .map(list -> {
                    List<CommitResponse> out = new ArrayList<>(list.size());
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            out.add(parseCommitFromGitCommitsApi((Map<String, Object>) m));
                        }
                    }
                    return out;
                })
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 두 커밋 간 diff.
     * Gitea GET /api/v1/repos/{owner}/{repo}/compare/{from}...{to}
     * 응답에서 files 배열 추출.
     */
    @SuppressWarnings("unchecked")
    public Mono<DiffResponse> diff(String repo, String fromSha, String toSha) {
        return webClient.get()
                .uri("/api/v1/repos/{owner}/{repo}/compare/{from}...{to}",
                        owner, repo, fromSha, toSha)
                .retrieve()
                .bodyToMono(Map.class)
                .map(this::parseDiffFiles)
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    /**
     * 특정 SHA 시점의 raw 콘텐츠.
     * Gitea GET /api/v1/repos/{owner}/{repo}/raw/{path}?ref={sha}
     * (롤백 시 과거 라벨 JSON 복원에 사용)
     */
    public Mono<String> getContent(String repo, String sha, String path) {
        return webClient.get()
                .uri(uri -> uri.path("/api/v1/repos/{owner}/{repo}/raw/{path}")
                        .queryParam("ref", sha)
                        .build(owner, repo, path))
                .retrieve()
                .bodyToMono(String.class)
                .timeout(CALL_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private CommitResponse parseCommitFromContentsApi(Map<String, Object> resp, String fallbackMessage, String fallbackAuthor) {
        Object commit = resp.get("commit");
        if (commit instanceof Map<?, ?> m) {
            String sha = strOrEmpty(m.get("sha"));
            String message = strOrDefault(m.get("message"), fallbackMessage);
            String author = extractAuthorName((Map<String, Object>) m, fallbackAuthor);
            Instant date = extractDate((Map<String, Object>) m);
            return new CommitResponse(sha, message, author, date);
        }
        return new CommitResponse("", fallbackMessage, fallbackAuthor, Instant.now());
    }

    @SuppressWarnings("unchecked")
    private CommitResponse parseCommitFromGitCommitsApi(Map<String, Object> resp) {
        String sha = strOrEmpty(resp.get("sha"));
        String message = strOrEmpty(resp.get("message"));
        String author = "";
        Instant date = Instant.now();

        Object commitObj = resp.get("commit");
        if (commitObj instanceof Map<?, ?> commitMap) {
            if (message.isEmpty()) {
                message = strOrEmpty(commitMap.get("message"));
            }
            author = extractAuthorName((Map<String, Object>) commitMap, "");
            date = extractDate((Map<String, Object>) commitMap);
        } else {
            // 톱-레벨에 author/created 가 있는 경우 처리
            author = extractAuthorName(resp, "");
            Object created = resp.get("created");
            if (created != null) {
                try { date = Instant.parse(created.toString()); } catch (Exception ignored) {}
            }
        }
        return new CommitResponse(sha, message, author, date);
    }

    @SuppressWarnings("unchecked")
    private DiffResponse parseDiffFiles(Map<String, Object> resp) {
        Object filesObj = resp.get("files");
        List<DiffFile> result = new ArrayList<>();
        if (filesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    String filename = strOrEmpty(m.get("filename"));
                    if (filename.isEmpty()) {
                        filename = strOrEmpty(m.get("path"));
                    }
                    String status = strOrEmpty(m.get("status"));
                    if (status.isEmpty()) {
                        status = strOrEmpty(m.get("change"));
                    }
                    int additions = intOrZero(m.get("additions"));
                    int deletions = intOrZero(m.get("deletions"));
                    String patch = strOrEmpty(m.get("patch"));
                    result.add(new DiffFile(filename, status, additions, deletions, patch.isEmpty() ? null : patch));
                }
            }
        }
        return new DiffResponse(result);
    }

    @SuppressWarnings("unchecked")
    private String extractAuthorName(Map<String, Object> commitMap, String fallback) {
        Object author = commitMap.get("author");
        if (author instanceof Map<?, ?> am) {
            Object name = am.get("name");
            if (name != null) return name.toString();
            Object login = am.get("login");
            if (login != null) return login.toString();
        }
        return fallback == null ? "" : fallback;
    }

    @SuppressWarnings("unchecked")
    private Instant extractDate(Map<String, Object> commitMap) {
        Object author = commitMap.get("author");
        if (author instanceof Map<?, ?> am) {
            Object date = am.get("date");
            if (date != null) {
                try { return Instant.parse(date.toString()); } catch (Exception ignored) {}
            }
        }
        return Instant.now();
    }

    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String strOrDefault(Object v, String fallback) {
        return v == null ? (fallback == null ? "" : fallback) : v.toString();
    }

    private static int intOrZero(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }
}
