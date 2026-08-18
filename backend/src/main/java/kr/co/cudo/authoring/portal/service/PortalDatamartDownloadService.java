package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.video.service.FrameImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 포털 데이터마트 작업 데이터 ZIP 다운로드 — <b>파일 단계</b>(경로 검증 + ZIP 스트리밍).
 * {@code GET /v1/portal/datamart/videos/{rawSn}/download} @design API-203, AC-034, AC-035
 *
 * <h3>ZIP 구조 (API-203 원문)</h3>
 * <pre>
 * {rawSn}/labels.json                        프레임별 라벨(본인 저장분 우선)
 * {rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg 비식별 프레임 이미지
 * {rawSn}/video.{ext}                        비식별 영상 — <b>있을 때만</b>
 * </pre>
 *
 * <h3>이 빈에는 {@code @Transactional} 이 없다 (의도)</h3>
 * <p>DB 단계는 {@link PortalDatamartDownloadTxService} 가 {@code readOnly} 트랜잭션에서 끝내고 값
 * 레코드만 넘긴다. 경로 검증·파일 open·스트리밍은 여기서 <b>트랜잭션 밖</b>에 둔다 — 영상이 포함되면
 * 응답이 GB 급이라 커넥션을 쥔 채 NAS I/O 를 하면 커넥션 기아가 난다. 두 책임을 한 빈에 합치거나
 * 여기에 {@code @Transactional} 을 되돌리면 그 경계가 사라진다.
 *
 * <h3>메모리에 적재하지 않는다</h3>
 * <p>{@link StreamingResponseBody} 로 흘려보낸다. ZIP 을 {@code byte[]} 로 만들면 영상 크기만큼
 * 힙을 잡아 OOM 이 된다.
 *
 * <h3>경로 판정기는 축마다 다르다 (Critical)</h3>
 * <ul>
 *   <li><b>프레임 이미지</b> — {@link StorageSubtreePolicy#verifyDeidentifiedFile}
 *       ({@code frames/deid/**}·{@code videos/**} 서브트리 규약).</li>
 *   <li><b>비식별 영상</b> — {@link VideoArtifactRootResolver#resolveRealPathUnder} +
 *       {@link VideoArtifactRootResolver#readableDeidVideoBases}. 영상은 co-locate
 *       ({@code dirname(원본)/{rawSn}/deid/})에 놓여 프레임용 판정기로는 <b>기본 형상에서 전건 거부</b>된다.
 *       같은 DB 값({@code DE_IDNTF_FILE_PATH_NM})을 읽는 형제 소비자({@code VideoStreamService})와
 *       같은 판정기를 쓴다.</li>
 * </ul>
 * <p>어느 축이든 ①판정이 돌려준 <b>실경로</b>를 그대로 쓰고 ②{@link FrameImageService#openNoFollow}
 * ({@code NOFOLLOW_LINKS})로 연다 — lexical 경로로 검증하고 lexical 경로로 열면 판정~open 사이에
 * 원본(마스킹 전) 파일 심링크로 교체하는 창이 남는다(CWE-59/367/359).
 *
 * <h3>원본 폴백은 없다 (AC-034 불변 규칙)</h3>
 * <p>비식별 경로가 없거나 검증에 실패하면 그 파일은 <b>그냥 빠진다</b>. 원본(비식별 이전) 영상·프레임은
 * 어떤 경로로도 ZIP 에 들어가지 않는다.
 */
@Slf4j
@Service
public class PortalDatamartDownloadService {

    /** 파일명 날짜 파트 — {@code yyyyMMdd}. */
    private static final DateTimeFormatter FILE_NAME_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** 확장자 allowlist 패턴 — 파일시스템 유래 문자열을 ZIP 엔트리명에 그대로 싣지 않기 위함. */
    private static final String SAFE_EXT = "[a-z0-9]{1,8}";

    /** 확장자를 신뢰할 수 없을 때의 기본값(비식별 산출물은 mp4 컨테이너다). */
    private static final String DEFAULT_VIDEO_EXT = "mp4";

    private final PortalDatamartDownloadTxService txService;
    private final VideoArtifactRootResolver artifactRootResolver;
    private final String deidentifiedPath;

    public PortalDatamartDownloadService(
            PortalDatamartDownloadTxService txService,
            VideoArtifactRootResolver artifactRootResolver,
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidentifiedPath) {
        this.txService = txService;
        this.artifactRootResolver = artifactRootResolver;
        this.deidentifiedPath = deidentifiedPath;
    }

    /** ZIP 엔트리 1건 — 검증을 통과한 <b>실경로</b>와 그 경로를 담을 엔트리명. */
    private record ZipFile(String entryName, Path realPath) {}

    /**
     * 다운로드 응답을 만든다. 게이트 3종(403/412/410)은 {@link PortalDatamartDownloadTxService#plan}
     * 이 순서대로 평가하며, 여기서는 통과한 계획서만 파일로 옮긴다.
     *
     * @throws CustomException 403 / 412 / 410 (계획 단계에서 전파)
     */
    public ResponseEntity<StreamingResponseBody> download(Long rawSn, TokenClaims actor) {
        PortalDatamartDownloadTxService.DownloadPlan plan = txService.plan(rawSn, actor);

        List<ZipFile> files = new ArrayList<>();
        files.addAll(resolveFrameImages(plan));
        resolveDeidVideo(plan).ifPresent(files::add);

        byte[] labelsJson = plan.labelsJson();
        String prefix = plan.rawSn() + "/";
        StreamingResponseBody body = out -> writeZip(out, prefix, labelsJson, files, plan.rawSn());

        // 서버 생성 고정명 — 사용자 입력이 섞이지 않아 CRLF 헤더 인젝션(CWE-113) 여지가 구조적으로 없다.
        String fileName = "portal-video-" + plan.rawSn() + "-" + LocalDate.now().format(FILE_NAME_DATE) + ".zip";
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "zip"))
                // 신고 게이트가 매 요청 평가되려면 클라이언트가 응답을 재사용하면 안 된다 — 캐시된
                // 산출물이 412 로 바뀐 뒤에도 그대로 재노출된다(CWE-359/525). 게이트가 걸린 미디어 공통 규칙.
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * 프레임 이미지 — <b>비식별 벌만</b>. 검증 실패 프레임은 사유 코드만 남기고 조용히 빠진다
     * (원본으로 대체하지 않는다).
     */
    private List<ZipFile> resolveFrameImages(PortalDatamartDownloadTxService.DownloadPlan plan) {
        Path base = Paths.get(deidentifiedPath).toAbsolutePath().normalize();
        List<ZipFile> files = new ArrayList<>(plan.frames().size());
        for (PortalDatamartDownloadTxService.FrameEntry frame : plan.frames()) {
            StorageSubtreePolicy.Verification verification =
                    StorageSubtreePolicy.verifyDeidentifiedFile(base, frame.deidImagePath());
            if (!verification.ok()) {
                // 경로 원문은 남기지 않는다(CWE-209/117) — 사유 코드만.
                log.warn("[PortalDownload] frame skipped rawSn={} frameNo={} verdict={}",
                        plan.rawSn(), frame.frameNo(), verification.verdict());
                continue;
            }
            files.add(new ZipFile(
                    "frames/" + ExportFileNaming.imageFileName(frame.frameNo()),
                    verification.path()));
        }
        return files;
    }

    /**
     * 비식별 <b>영상</b> — 경로가 없으면(신규·미비식별) 엔트리 자체를 만들지 않는다(AC-034).
     * 허용 base 는 구 위치({@code {deid_base}/videos/{rawSn}})와 co-locate 위치를 모두 포함하며,
     * 그중 하나라도 통과하면 그 <b>실경로</b>를 쓴다.
     */
    private java.util.Optional<ZipFile> resolveDeidVideo(PortalDatamartDownloadTxService.DownloadPlan plan) {
        String deidPath = plan.deidVideoPath();
        if (deidPath == null || deidPath.isBlank()) {
            // 신고 구간은 여기 오기 전에 412 로 끝난다(TxService 클래스 주석) — 여기서의 null 은
            // "비식별 이력이 없다"는 뜻이며 그대로 영상 없는 ZIP 이 정상이다.
            return java.util.Optional.empty();
        }
        Path candidate;
        try {
            candidate = Paths.get(deidPath);
        } catch (RuntimeException e) {
            log.warn("[PortalDownload] deid video path unusable rawSn={} causeType={}",
                    plan.rawSn(), e.getClass().getSimpleName());
            return java.util.Optional.empty();
        }
        for (Path base : artifactRootResolver.readableDeidVideoBases(plan.rawSn(), plan.rawFilePathNm())) {
            Path real;
            try {
                real = VideoArtifactRootResolver.resolveRealPathUnder(candidate, base);
            } catch (RuntimeException e) {
                continue; // 이 base 밖 — 다음 후보로.
            }
            // 심링크·부재·디렉터리는 산출물이 아니다(fail-closed) — open 규약과 동일 판정.
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            return java.util.Optional.of(new ZipFile("video." + safeExtension(real), real));
        }
        log.warn("[PortalDownload] deid video rejected rawSn={} — outside readable deid bases", plan.rawSn());
        return java.util.Optional.empty();
    }

    /**
     * 파일명에서 확장자를 뽑되 <b>allowlist 통과분만</b> 사용한다 — 파일시스템 유래 문자열이 ZIP
     * 엔트리명에 그대로 실리면 경로 조작 문자가 섞일 수 있다. 위반 시 {@code mp4} 로 마감한다.
     */
    private static String safeExtension(Path file) {
        Path name = file.getFileName();
        if (name == null) {
            return DEFAULT_VIDEO_EXT;
        }
        String fileName = name.toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return DEFAULT_VIDEO_EXT;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches(SAFE_EXT) ? ext : DEFAULT_VIDEO_EXT;
    }

    /** ZIP 본문 — 파일은 한 번에 하나씩만 연다(FD 고갈 방지). */
    private void writeZip(java.io.OutputStream out, String prefix, byte[] labelsJson,
                          List<ZipFile> files, long rawSn) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(prefix + "labels.json"));
            zip.write(labelsJson);
            zip.closeEntry();

            for (ZipFile file : files) {
                FrameImageService.OpenedFile opened;
                try {
                    // 판정에 쓴 실경로를 그대로, NOFOLLOW 로 연다(TOCTOU — CWE-367/59).
                    opened = FrameImageService.openNoFollow(file.realPath());
                } catch (IOException e) {
                    // 판정~open 사이에 사라졌거나 심링크로 교체됨 — 그 파일만 빠진다(원본 대체 금지).
                    log.warn("[PortalDownload] entry open failed rawSn={} entry={} reason={}",
                            rawSn, file.entryName(), e.getClass().getSimpleName());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(prefix + file.entryName()));
                try (InputStream in = opened.stream()) {
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }
}
