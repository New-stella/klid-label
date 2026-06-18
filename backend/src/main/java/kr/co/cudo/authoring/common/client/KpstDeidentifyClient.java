package kr.co.cudo.authoring.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.client.dto.KpstDeleteResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProgressRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.KpstUploadResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ㈜KPST 비식별화 솔루션 폴링 경로 클라이언트 — Phase 1 신설.
 *
 * <p>규격 정본: {@code docs/v2-wiki/22-deid-solution-api.md}. 본 클라이언트는 13종 중 핵심 6종
 * (연결확인·업로드·프로젝트생성·진행조회·다운로드·삭제)을 구현한다. 기존 콜백 모델
 * {@link DeidentifyClient} 와 병행하며 변경하지 않는다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>TLS 자체 CA (CWE-295): 신뢰 체인은 {@code KpstWebClientConfig} 가 ca.crt 로 구성한
 *       {@code kpstDeidWebClient} 빈에서만 주입된다. 본 클래스는 WebClient 를 직접 만들지 않는다.</li>
 *   <li>SSRF (CWE-918): base-url 은 application.yml 설정값(빈 주입)만 사용. 사용자 입력으로 URL/호스트
 *       를 구성하지 않으며, 경로는 모두 상수다.</li>
 *   <li>경로 순회 (CWE-22): {@link #download} 는 절대경로 target 을 거부하고 저장 경로를 normalize
 *       후 base 디렉터리 내부인지 검증하며, write 직전 부모 실경로(toRealPath)로 심볼릭 링크 우회까지
 *       차단한다. base 이탈은 외부 호출 이전에 거부된다. {@link #upload} 의 subdir 도 정규식으로
 *       정규화한다.</li>
 *   <li>입력 검증 (CWE-434/CWE-20): {@link #upload} 는 파일 개수(1~50)·존재 여부·확장자 allowlist·
 *       subdir 문자 집합을 외부 호출 이전에 검증한다.</li>
 *   <li>부분 파일 방지 (CWE-459/404): {@link #download} 는 임시(.part) 파일에 받아 완료 시에만
 *       atomic move 하며, 에러 시 임시 파일을 정리해 불완전 비식별 영상의 후속 파이프라인 유입을
 *       차단한다. DataBuffer 는 Path 오버로드로 자동 release 되어 누수가 없다.</li>
 *   <li>정보 유출 (CWE-209): 외부 API 원문 에러 본문/스택트레이스를 예외/로그에 노출하지 않는다.
 *       상태 코드와 예외 클래스명만 기록한다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentifyClient {

    /** API 경로 상수 (규격 §22.2). 사용자 입력으로 구성 금지. */
    private static final String PATH_CONNECT = "/";
    private static final String PATH_UPLOAD = "/upload";
    private static final String PATH_PROJECT = "/project";
    private static final String PATH_RETRIEVE_PROGRESS = "/retrieve_progress";
    private static final String PATH_DOWNLOAD = "/download";
    private static final String PATH_DELETE_PROJECT_ID = "/delete_project_id";

    private static final String CONNECT_OK_BODY = "Connect";
    private static final String UPLOAD_FIELD_FILES = "files";
    private static final String UPLOAD_FIELD_SUBDIR = "subdir";
    private static final String QUERY_DATASET_ID = "dataset_id";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

    /** 업로드 입력 가드 (CWE-434/CWE-20). */
    private static final int UPLOAD_MIN_FILES = 1;
    private static final int UPLOAD_MAX_FILES = 50;
    /** 영상 확장자 allowlist — TusUploadService.ALLOWED_EXTENSIONS 와 정합. */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");
    /** subdir 허용 문자 — 영문/숫자/하이픈/언더스코어/한글. {@code .. / \} 차단(CWE-22). */
    private static final Pattern SUBDIR_PATTERN = Pattern.compile("^[\\p{IsHangul}A-Za-z0-9_-]+$");

    /** 다운로드 임시파일 접미사 — 완료 시 atomic move 로 정식 경로로 전환. */
    private static final String PART_SUFFIX = ".part";
    private static final String DELETE_RESULT_SUCCESS = "success";

    private final WebClient webClient;
    private final WebClient uploadWebClient;
    private final HttpClient progressHttpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public KpstDeidentifyClient(@Qualifier("kpstDeidWebClient") WebClient webClient,
                                @Qualifier("kpstDeidUploadWebClient") WebClient uploadWebClient,
                                @Qualifier("kpstDeidProgressHttpClient") HttpClient progressHttpClient,
                                @Qualifier("kpstDeidCircuitBreaker") CircuitBreaker circuitBreaker,
                                RetryRegistry retryRegistry) {
        this.webClient = webClient;
        this.uploadWebClient = uploadWebClient;
        this.progressHttpClient = progressHttpClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retryRegistry.retry("kpstDeid");
    }

    /**
     * 서버 연결 확인 — {@code GET /}. 응답 {@code "Connect"} 면 true.
     */
    public boolean connect() {
        String body = webClient.get()
                .uri(PATH_CONNECT)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorResume(e -> Mono.just(""))
                .blockOptional(DEFAULT_TIMEOUT)
                .orElse("");
        return CONNECT_OK_BODY.equalsIgnoreCase(body.trim());
    }

    /**
     * 영상 파일 업로드 — {@code POST /upload} (multipart/form-data).
     *
     * @param files  업로드 대상 로컬 파일 경로 목록 (1~50)
     * @param subdir 저장 하위 폴더명 (null/blank 면 미전송 → 기본 디렉터리)
     * @return 저장 경로/파일목록을 담은 응답 (그대로 {@link #createProject} input_path 로 사용)
     */
    public KpstUploadResponse upload(List<Path> files, String subdir) {
        validateUploadInput(files, subdir);
        // multipart 바디는 재구독 시 consumed stream 을 재전송하지 못해 RetryOperator 와 안전하게
        // 결합되지 않는다(400 No file part 유발). Mono.defer 로 매 구독마다 MultipartBodyBuilder 를
        // 새로 생성해 재발행을 보장한다(서킷브레이커/재시도 유지). — DEV_FIX HIGH
        return Mono.defer(() -> uploadWebClient.post()
                        .uri(PATH_UPLOAD)
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(BodyInserters.fromMultipartData(buildMultipart(files, subdir)))
                        .retrieve()
                        .bodyToMono(KpstUploadResponse.class)
                        .timeout(UPLOAD_TIMEOUT))
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(UPLOAD_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 업로드 응답이 비어있습니다."));
    }

    /** 매 구독마다 새 multipart 바디 생성(재시도 시 재발행 보장). */
    private org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>>
            buildMultipart(List<Path> files, String subdir) {
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        for (Path file : files) {
            mb.part(UPLOAD_FIELD_FILES, new FileSystemResource(file));
        }
        if (subdir != null && !subdir.isBlank()) {
            mb.part(UPLOAD_FIELD_SUBDIR, subdir);
        }
        return mb.build();
    }

    /**
     * 업로드 입력 검증(CWE-434/CWE-20) — 외부 호출 이전에 거부.
     * 파일 개수(1~50) · 각 파일 존재 · 확장자 allowlist · subdir 정규화.
     */
    private void validateUploadInput(List<Path> files, String subdir) {
        if (files == null || files.size() < UPLOAD_MIN_FILES || files.size() > UPLOAD_MAX_FILES) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "업로드 파일 개수는 " + UPLOAD_MIN_FILES + "~" + UPLOAD_MAX_FILES + "개여야 합니다.");
        }
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "업로드 대상 파일이 존재하지 않습니다.");
            }
            String ext = extensionOf(file);
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
            }
        }
        if (subdir != null && !subdir.isBlank() && !SUBDIR_PATTERN.matcher(subdir).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "하위 폴더명에 허용되지 않는 문자가 포함되어 있습니다.");
        }
    }

    private String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1)
                ? ""
                : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 프로젝트 생성 및 작업 등록 — {@code POST /project} (application/json).
     * 동일 이름 존재 시 외부 409 → {@link ErrorCode#CONFLICT}.
     */
    public KpstProjectResponse createProject(KpstProjectRequest request) {
        return webClient.post()
                .uri(PATH_PROJECT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(KpstProjectResponse.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(DEFAULT_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 프로젝트 생성 응답이 비어있습니다."));
    }

    /**
     * 진행 상황 조회 — {@code GET /retrieve_progress} (JSON 바디 필수).
     *
     * <p>실서버는 GET 요청에도 JSON 바디 필터(reqUserId/prjId)를 강제하며, 쿼리 전용 호출은
     * {@code HTTP 400 (Invalid JSON body)} 로 거부됨을 실서버에서 확인했다. 따라서 reqUserId/prjId 를
     * JSON 바디({@link KpstProgressRequest})로 전송한다(서버가 camelCase 키 수용).
     *
     * <p>전송 경로(중요): Spring {@code WebClient.method(GET).bodyValue(...)} 는 reactor-netty 가 GET
     * 바디 바이트를 실제로 전송하지 않아(서버가 본문을 무기한 대기 → 타임아웃) 동작하지 않는다.
     * 따라서 바디 전송이 가능한 저수준 {@code HttpClient.request(GET).send(...)} 경로로 전송하고,
     * 응답 JSON 을 {@link KpstProgressResponse} 로 역직렬화한다. Resilience4j retry/circuitBreaker·
     * timeout·onErrorMap·blockOptional 파이프라인 구조는 그대로 유지한다.
     *
     * @param reqUserId 요청자 ID (필수)
     * @param prjId     프로젝트 ID 필터 (필수 — 폴링 시 단일 프로젝트 대상)
     */
    public KpstProgressResponse retrieveProgress(String reqUserId, Long prjId) {
        byte[] payload = serializeProgressRequest(new KpstProgressRequest(reqUserId, prjId));
        return progressHttpClient
                .headers(h -> {
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE,
                            MediaType.APPLICATION_JSON_VALUE);
                    // GET 은 기본적으로 바디리스로 인코딩된다 — Content-Length 를 명시해야 Netty 가
                    // 바디 바이트를 프레이밍·전송한다(서버의 JSON 바디 필수 요구 충족).
                    h.set(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH, payload.length);
                })
                .request(io.netty.handler.codec.http.HttpMethod.GET)
                .uri(PATH_RETRIEVE_PROGRESS)
                .send((req, out) -> out.sendByteArray(Mono.just(payload)))
                .responseSingle((resp, content) -> content.asByteArray()
                        .defaultIfEmpty(new byte[0])
                        .map(body -> {
                            int status = resp.status().code();
                            if (status < 200 || status >= 300) {
                                // 본문 원문은 노출하지 않는다(CWE-209) — 상태 코드만 매핑.
                                throw mapStatus(status);
                            }
                            return deserializeProgress(body);
                        }))
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .blockOptional(DEFAULT_TIMEOUT)
                .orElseThrow(() -> new CustomException(ErrorCode.EXTERNAL_API_ERROR,
                        "비식별 진행 조회 응답이 비어있습니다."));
    }

    private byte[] serializeProgressRequest(KpstProgressRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 진행 조회 요청 직렬화에 실패했습니다.");
        }
    }

    private KpstProgressResponse deserializeProgress(byte[] body) {
        try {
            return objectMapper.readValue(body, KpstProgressResponse.class);
        } catch (IOException e) {
            // 본문 원문은 노출하지 않는다(CWE-209) — 예외 종류만 변환.
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 진행 조회 응답 파싱에 실패했습니다.");
        }
    }

    /**
     * 마스킹 결과 다운로드 — {@code GET /download?dataset_id=…}. 미완료(state≠2) 시 외부 403.
     *
     * <p>경로 순회 방어 (CWE-22): {@code target} 은 {@code baseDir} 내부여야 한다. 검증은 외부 호출
     * 이전에 수행되어 base 이탈 요청이 발생하지 않는다.
     *
     * @param datasetId 데이터셋 ID
     * @param baseDir   저장 허용 기준 디렉터리
     * @param target    저장 파일 경로 (baseDir 내부)
     * @return 저장된 파일 경로
     */
    public Path download(Long datasetId, Path baseDir, Path target) {
        Path resolvedTarget = resolveWithinBase(baseDir, target);
        try {
            Files.createDirectories(resolvedTarget.getParent());
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "다운로드 저장 경로 준비 실패");
        }
        verifyRealPathWithinBase(baseDir, resolvedTarget.getParent());

        // 부분 파일 잔존 방지(CWE-459/404): 임시(.part) 경로에 받고, 완료 시에만 atomic move.
        Path tmp = resolvedTarget.resolveSibling(resolvedTarget.getFileName() + PART_SUFFIX);
        Flux<org.springframework.core.io.buffer.DataBuffer> dataBuffer = webClient.get()
                .uri(uriBuilder -> uriBuilder.path(PATH_DOWNLOAD)
                        .queryParam(QUERY_DATASET_ID, datasetId)
                        .build())
                .retrieve()
                .bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                .timeout(DOWNLOAD_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate);

        try {
            Files.deleteIfExists(tmp);
            // Path 오버로드: write 후 각 DataBuffer 를 자동 release(에러 경로 포함) → 누수 없음.
            org.springframework.core.io.buffer.DataBufferUtils
                    .write(dataBuffer, tmp, java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE)
                    .block(DOWNLOAD_TIMEOUT);
            // 무결성(CWE-459/404 방어): 0바이트 산출물은 불완전 비식별 — 정식 경로 승격 차단.
            if (Files.size(tmp) <= 0) {
                deleteQuietly(tmp);
                log.warn("[KpstDeid] download produced empty file datasetId={}", datasetId);
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "다운로드 결과가 비어있습니다.");
            }
            moveAtomically(tmp, resolvedTarget);
            return resolvedTarget;
        } catch (CustomException e) {
            deleteQuietly(tmp);
            throw e;
        } catch (Exception e) {
            // 에러(네트워크/타임아웃/서킷오픈) 시 부분 파일 정리 — 불완전 비식별 영상 유입 차단.
            deleteQuietly(tmp);
            log.warn("[KpstDeid] download failed type={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "다운로드 파일 저장 실패");
        }
    }

    /** 완료된 임시파일을 정식 경로로 원자적 이동(불가 환경은 replace 로 폴백). */
    private void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            log.warn("[KpstDeid] partial file cleanup failed");
        }
    }

    /**
     * 프로젝트 삭제(ID 기준) — {@code POST /delete_project_id} (application/json).
     */
    public void deleteProject(Long prjId, String userId) {
        Map<String, Object> body = Map.of("project_id", prjId, "user_id", userId);
        KpstDeleteResponse resp = webClient.post()
                .uri(PATH_DELETE_PROJECT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(KpstDeleteResponse.class)
                .timeout(DEFAULT_TIMEOUT)
                .transformDeferred(RetryOperator.of(retry))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(this::translate)
                .block(DEFAULT_TIMEOUT);
        if (resp == null || !DELETE_RESULT_SUCCESS.equalsIgnoreCase(resp.result())) {
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 프로젝트 삭제에 실패했습니다.");
        }
    }

    /**
     * 저장 경로가 base 디렉터리 내부인지 검증(CWE-22). 이탈 시 차단.
     *
     * <p>절대경로 {@code target} 은 거부한다(base-relative 만 허용 — 절대경로 우회 차단).
     * normalize 후 base 내부인지 startsWith 로 검증한다. 심볼릭 링크 우회는 write 직전
     * {@link #verifyRealPathWithinBase} 의 실경로 검증으로 추가 차단한다.
     */
    private Path resolveWithinBase(Path baseDir, Path target) {
        if (target.isAbsolute()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "다운로드 저장 경로는 상대 경로만 허용됩니다.");
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(target).toAbsolutePath().normalize();
        if (!resolved.startsWith(base)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "다운로드 저장 경로가 허용 디렉터리를 벗어났습니다.");
        }
        return resolved;
    }

    /**
     * 심볼릭 링크 우회 차단(CWE-22) — 디렉터리 생성 후 부모의 실경로(toRealPath)가 base 의
     * 실경로 내부인지 검증한다. 중간 경로가 base 밖으로 향하는 심링크면 거부.
     */
    private void verifyRealPathWithinBase(Path baseDir, Path parentDir) {
        try {
            Path baseReal = baseDir.toRealPath();
            // 최종 경로 요소가 심볼릭 링크면 base 밖으로 우회할 수 있으므로 명시적으로 거부(CWE-22).
            // (이전의 이중 toRealPath 는 두 번째 호출이 링크를 따라가 NOFOLLOW 효과를 상쇄했음 — DEV_FIX LOW.)
            if (Files.isSymbolicLink(parentDir)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "다운로드 저장 경로가 허용 디렉터리를 벗어났습니다.");
            }
            Path parentReal = parentDir.toRealPath();
            if (!parentReal.startsWith(baseReal)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "다운로드 저장 경로가 허용 디렉터리를 벗어났습니다.");
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "다운로드 저장 경로 검증 실패");
        }
    }

    /**
     * 외부 API 예외를 내부 표준 예외로 변환(CWE-209: 원문 노출 금지).
     * 상태 코드만 매핑하고 본문/스택트레이스는 예외 메시지에 포함하지 않는다.
     */
    private Throwable translate(Throwable e) {
        if (e instanceof CustomException) {
            return e;
        }
        if (e instanceof WebClientResponseException ex) {
            int status = ex.getStatusCode().value();
            return mapStatus(status);
        }
        log.warn("[KpstDeid] external call failed type={}", e.getClass().getSimpleName());
        return new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 솔루션 호출에 실패했습니다.");
    }

    /** HTTP 상태 코드 → 내부 표준 예외(CWE-209: 원문/스택트레이스 미노출, 상태 코드만 매핑). */
    private CustomException mapStatus(int status) {
        log.warn("[KpstDeid] external error status={}", status);
        ErrorCode code = switch (status) {
            case 400 -> ErrorCode.INVALID_INPUT;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            default -> ErrorCode.EXTERNAL_API_ERROR;
        };
        return new CustomException(code, "비식별 솔루션 호출 실패(status=" + status + ")");
    }
}
