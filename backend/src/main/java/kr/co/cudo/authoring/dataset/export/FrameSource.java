package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
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
        String relPath = (kind == ExportKind.ORIGINAL) ? frame.getSrcFilePathNm() : frame.getDeidFilePath();
        if (relPath == null || relPath.isBlank()) {
            return Optional.empty();
        }
        // 허용 base — 종류별 <b>단일</b> base 강제 + 실경로/심링크 재검증(CWE-22/59).
        //   · ORIGINAL: rawBase 단일(비식별 픽셀이 원본 벌로 새지 않도록 PII 격리).
        //   · DEIDENTIFIED: deidBase 단일 + 비식별 전용 서브트리(frames/deid·videos) 강제.
        // E-ISSUE-22 — 구 구현은 DEIDENTIFIED 에 rawBase 폴백을 허용했다(해상도 파생이 raw base 하위에
        // 기록되던 결함을 우회하려는 목적). 그 결과 <b>모든 영상</b>의 비식별 벌이 raw base 파일을 "비식별본"
        // 으로 수용하는 fail-open 이 상시 열려 있었다. 파생 산출물을 deid base 로 이동(E-ISSUE-21)한 뒤
        // 폴백을 제거한다 — 비식별본이 없는 프레임은 원본으로 대체하지 않고 건너뛴다(fail-closed).
        Path base = (kind == ExportKind.ORIGINAL) ? rawBase : deidBase;
        Optional<Path> resolved = resolveUnder(base, relPath, rawSn, frame, kind);
        if (resolved.isPresent()) {
            return resolved;
        }
        // 미해석(부재/이탈/서브트리 위반) — fail-secure 로 건너뜀. 경로 원문 미노출.
        log.warn("[FrameSource] frame path unresolved skipped rawSn={} frameNo={} kind={}", rawSn, frameNoOf(frame), kind);
        return Optional.empty();
    }

    /**
     * 단일 base 하위로 경로를 안전 해석한다 — traversal(CWE-22) + 존재/정규파일 + 심링크(CWE-59) 하드닝을
     * 모두 해당 base 기준으로 적용한다. 통과 시 실제 파일 경로, 아니면 {@link Optional#empty()}.
     */
    private Optional<Path> resolveUnder(Path base, String relPath, long rawSn, LsDataSrc frame, ExportKind kind) {
        if (kind == ExportKind.DEIDENTIFIED) {
            // H-2/H-4 — 비식별 벌 판정은 <b>단일 판정기</b>에 위임한다. 서브트리 검사가 실경로(toRealPath)
            // 기준으로 수행되므로, base 내부 심링크(frames/deid → frames/raw)로 원본 프레임을 비식별 벌에
            // 밀어넣는 우회(CWE-59/CWE-359)가 차단된다. 감사 배치도 같은 메서드를 호출한다.
            StorageSubtreePolicy.Verification v = StorageSubtreePolicy.verifyDeidentifiedFile(base, relPath);
            if (!v.ok()) {
                log.warn("[FrameSource] deid frame rejected rawSn={} frameNo={} verdict={}",
                        rawSn, frameNoOf(frame), v.verdict());
                return Optional.empty();
            }
            return Optional.of(v.path());
        }

        Path resolved;
        try {
            resolved = FrameImageService.resolveSafe(base, relPath);
        } catch (CustomException e) {
            // base 이탈 — fail-secure(로그는 최종 skip 시 상위에서).
            return Optional.empty();
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return Optional.empty();
        }
        // CWE-59: 심링크 하드닝 — lexical containment 통과 후에도 실제(심링크 해석) 경로가
        // base 실제경로 하위인지 재검증. base 내부에 base 밖을 가리키는 symlink 우회를 차단한다.
        // toRealPath 는 존재 파일에만 유효하므로 위 존재/정규파일 검증 이후에 수행한다.
        Path realResolved;
        try {
            realResolved = resolved.toRealPath();
            Path realBase = base.toRealPath();
            if (!realResolved.startsWith(realBase)) {
                log.warn("[FrameSource] symlink escaping base skipped rawSn={} frameNo={}", rawSn, frameNoOf(frame));
                return Optional.empty();
            }
            // ORIGINAL 벌도 실경로 기준으로 원본 서브트리를 강제한다 — 두 base 가 동일한 운영 환경에서
            // frames/raw/** 심링크가 frames/deid/** 로 새는(그 역방향) 혼입도 함께 차단한다.
            if (StorageSubtreePolicy.isDeidentifiedArtifact(realBase, realResolved)) {
                log.warn("[FrameSource] original path resolves into deidentified subtree skipped rawSn={} frameNo={}",
                        rawSn, frameNoOf(frame));
                return Optional.empty();
            }
        } catch (IOException e) {
            // 실제 경로 해석 실패 — fail-secure 로 건너뜀. 경로 원문 미노출.
            log.warn("[FrameSource] realpath resolution failed skipped rawSn={} frameNo={}", rawSn, frameNoOf(frame));
            return Optional.empty();
        }
        // <b>검증한 경로를 그대로 돌려준다</b>(CWE-367 TOCTOU). 구 구현은 realResolved 로 검증하고
        // lexical 경로(resolved)를 반환해, 소비자(DatasetExportWriter.Files.copy)가 <b>검증하지 않은 경로</b>를
        // 열었다. 검증과 사용 사이에 경로 구성요소가 심링크로 교체되면 검사를 통과한 것과 다른 파일이 복사된다.
        // DEIDENTIFIED 분기는 이미 검증 결과(v.path())를 반환하고 있어 두 분기의 계약이 어긋나 있었다.
        return Optional.of(realResolved);
    }

    private static Object frameNoOf(LsDataSrc frame) {
        return frame.getFrameNo();
    }
}
