package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.service.FrameImageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 프레임 원천 이미지 경로 해석기 — 산출 종류에 맞는 실제 파일 경로를 안전하게 반환한다.
 *
 * <p>경로 정합: 프레임 추출({@code FfmpegFrameExtractor})은 원본을 {@code raw-path} 하위,
 * 비식별을 {@code deidentified-path} 하위에 기록하며 그 절대경로를
 * {@link LsDataSrc#getSrcFilePathNm()}(원본) / {@link LsDataSrc#getDeidFilePath()}(비식별)에 저장한다.
 * 여기서는 종류별 base 하위인지 {@link FrameImageService#resolveSafe} 로 재검증한다(악의적 DB 경로 방어).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>Path Traversal(CWE-22)</b>: DB 에 저장된 경로도 신뢰하지 않고 종류별 base 하위 포함을 강제.
 *       base 이탈/blank/null 은 조용히 {@link Optional#empty()} 로 fail-secure 처리(건너뜀).</li>
 *   <li><b>Symlink 이탈(CWE-59)</b>: lexical {@code normalize()} 만으로는 base 내부에 base 밖을
 *       가리키는 심볼릭 링크가 있으면 우회 가능하다. 산출물이 데이터마트(외부)로 흐르므로,
 *       파일이 존재하면 {@code toRealPath()}(심링크 해석) 실제 경로가 여전히
 *       {@code base.toRealPath()} 하위인지 재검증하고 이탈 시 fail-secure({@link Optional#empty()}).</li>
 *   <li><b>로그(CWE-117/209)</b>: 경로 원문 대신 rawSn/frameNo 만 로깅.</li>
 * </ul>
 */
@Component
public class FrameSource {

    private static final Logger log = LoggerFactory.getLogger(FrameSource.class);

    private final Path rawBase;
    private final Path deidBase;

    public FrameSource(
            @Value("${authoring.storage.raw-path:./storage/raw}") String rawPath,
            @Value("${authoring.storage.deidentified-path:./storage/deidentified}") String deidPath) {
        this.rawBase = Paths.get(rawPath).toAbsolutePath().normalize();
        this.deidBase = Paths.get(deidPath).toAbsolutePath().normalize();
    }

    /**
     * 산출 종류에 맞는 프레임 원천 이미지의 안전한 절대경로를 반환한다.
     *
     * @param rawSn 영상 PK (로깅용)
     * @param kind  산출 종류(원본/비식별)
     * @param frame 프레임 엔티티(경로 원천)
     * @return 존재하는 정규 파일이며 base 하위이면 그 경로, 아니면 {@link Optional#empty()}
     */
    public Optional<Path> resolveImage(long rawSn, ExportKind kind, LsDataSrc frame) {
        if (kind == null || frame == null) {
            return Optional.empty();
        }
        Path base = (kind == ExportKind.ORIGINAL) ? rawBase : deidBase;
        String relPath = (kind == ExportKind.ORIGINAL) ? frame.getSrcFilePathNm() : frame.getDeidFilePath();
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        Path resolved;
        try {
            resolved = FrameImageService.resolveSafe(base, relPath);
        } catch (CustomException e) {
            // base 이탈 등 — fail-secure 로 건너뜀. 경로 원문 미노출.
            log.warn("[FrameSource] unsafe frame path skipped rawSn={} frameNo={}", rawSn, frameNoOf(frame));
            return Optional.empty();
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        // CWE-59: 심링크 하드닝 — lexical containment 통과 후에도 실제(심링크 해석) 경로가
        // base 실제경로 하위인지 재검증. base 내부에 base 밖을 가리키는 symlink 우회를 차단한다.
        // toRealPath 는 존재 파일에만 유효하므로 위 존재/정규파일 검증 이후에 수행한다.
        try {
            Path realResolved = resolved.toRealPath();
            Path realBase = base.toRealPath();
            if (!realResolved.startsWith(realBase)) {
                log.warn("[FrameSource] symlink escaping base skipped rawSn={} frameNo={}", rawSn, frameNoOf(frame));
                return Optional.empty();
            }
        } catch (IOException e) {
            // 실제 경로 해석 실패 — fail-secure 로 건너뜀. 경로 원문 미노출.
            log.warn("[FrameSource] realpath resolution failed skipped rawSn={} frameNo={}", rawSn, frameNoOf(frame));
            return Optional.empty();
        }
        return Optional.of(resolved);
    }

    private static Object frameNoOf(LsDataSrc frame) {
        return frame.getFrameNo();
    }
}
