package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 학습데이터 산출 경로 리졸버 — {@code {영상루트}/v{n}/{orgnl|deid}/} 를 안전하게 계산한다.
 *
 * <h3>영상 루트(A-1, Phase 5A)</h3>
 * <p>구 구조는 고정 {@code labeling_root} 하위였으나, 관제가 <b>경로 1개</b>로 산출물 전부를 픽업할 수
 * 있도록 <b>원본 영상과 같은 디렉터리 하위</b>({@code dirname(RAW_FILE_PATH_NM)/{rawSn}/})로 옮겼다.
 * base 가 DB 값에서 도출되는 동적 값이 되므로 검증은 {@link VideoArtifactRootResolver} 의 2단계
 * (고정 allowlist → 검증된 base 기준 target 재검증)를 그대로 사용한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>입력 검증(CWE-20)</b>: {@code videoId ≥ 1}, {@code version ≥ 1}, {@code kind != null}
 *       이 아니면 {@link ErrorCode#INVALID_INPUT}.</li>
 *   <li><b>Path Traversal/Symlink(CWE-22/59)</b>: base 는 고정 allowlist 하위 + 실경로 재검증,
 *       target 은 그 base 기준 {@code normalize()} + 실경로 재검증. 위반 시 {@link ErrorCode#FORBIDDEN}
 *       이며 <b>기본 루트로 폴백하지 않는다</b>(fail-secure).</li>
 * </ul>
 */
@Component
public class DatasetExportPathResolver {

    private final VideoArtifactRootResolver rootResolver;

    public DatasetExportPathResolver(VideoArtifactRootResolver rootResolver) {
        this.rootResolver = rootResolver;
    }

    /**
     * 영상 루트({@code {base}/{rawSn}}) — {@code LS_DATASET_EXPORT.EXPORT_PATH_NM} 에 적재되는 값이며,
     * 관제는 이 경로 하위에서 {@code deid/}(비식별 영상)와 {@code v1..vN/}(버전별 산출)을 모두 본다.
     *
     * @param videoId       대상 영상 ID(≥1)
     * @param rawFilePathNm {@code LS_DATA_RAW.RAW_FILE_PATH_NM}(co-locate 전략에서만 사용)
     * @throws CustomException 입력 검증 실패(INVALID_INPUT) 또는 base/target 이탈(FORBIDDEN)
     */
    public Path resolveVideoRoot(long videoId, String rawFilePathNm) {
        return rootResolver.videoRoot(videoId, rawFilePathNm);
    }

    /**
     * 산출 경로 계산 — {@code {영상루트}/v{version}/{orgnl|deid}}.
     *
     * @param videoId       대상 영상 ID(≥1)
     * @param rawFilePathNm {@code LS_DATA_RAW.RAW_FILE_PATH_NM}(co-locate 전략에서만 사용)
     * @param kind          산출 종류(원본/비식별)
     * @param version       산출 버전(≥1)
     * @throws CustomException 입력 검증 실패(INVALID_INPUT) 또는 base/target 이탈(FORBIDDEN)
     */
    public Path resolve(long videoId, String rawFilePathNm, ExportKind kind, int version) {
        if (version < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "version은 1 이상이어야 합니다.");
        }
        if (kind == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출 종류(kind)는 필수입니다.");
        }
        Path videoRoot = resolveVideoRoot(videoId, rawFilePathNm);
        // ②target 무결성 — ①을 통과한 base(요청 스코프 지역변수) 기준으로만 조립·재검증한다.
        return VideoArtifactRootResolver.resolveUnder(videoRoot, "v" + version, kind.segment());
    }
}
