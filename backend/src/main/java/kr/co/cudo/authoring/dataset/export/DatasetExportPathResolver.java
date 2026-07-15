package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 학습데이터 산출 경로 리졸버 — {@code {labeling_root}/{RAW_SN}/v{n}/{orgnl|deid}/} 를
 * 안전하게 계산한다(버전이 orgnl/deid 상위).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>입력 검증(CWE-20)</b>: {@code videoId ≥ 1}, {@code version ≥ 1} 이 아니면
 *       {@link ErrorCode#INVALID_INPUT} 으로 거부한다.</li>
 *   <li><b>Path Traversal(CWE-22)</b>: 계산 결과를 {@code normalize()} 후 반드시
 *       labeling_root(정규화된 절대경로) 하위인지 {@code startsWith} 로 검증한다. 세그먼트 소스가
 *       모두 타입 안전({@code long}/{@code int}/{@code enum})이라 {@code ..}·절대경로 주입 표면이
 *       없지만, 설정된 root 자체의 이상(상대·심링크 등)과 향후 변경에 대비한 방어선을 유지한다.
 *       위반 시 {@link ErrorCode#FORBIDDEN}.</li>
 * </ul>
 * (기존 {@code FrameImageService.resolveSafe} 의 base-inclusion 방어 패턴과 동형.)
 */
@Component
public class DatasetExportPathResolver {

    private final Path labelingRoot;

    public DatasetExportPathResolver(
            @Value("${authoring.storage.labeling-path:./storage/labeling}") String labelingRoot) {
        this.labelingRoot = Paths.get(labelingRoot).toAbsolutePath().normalize();
    }

    /**
     * 산출 경로 계산.
     *
     * @param videoId 대상 영상 ID(≥1)
     * @param kind    산출 종류(원본/비식별)
     * @param version 산출 버전(≥1)
     * @return {@code {labeling_root}/{videoId}/v{version}/{orgnl|deid}} 절대경로
     * @throws CustomException 입력 검증 실패(INVALID_INPUT) 또는 base 이탈(FORBIDDEN)
     */
    public Path resolve(long videoId, ExportKind kind, int version) {
        if (videoId < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "videoId는 1 이상이어야 합니다.");
        }
        if (version < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "version은 1 이상이어야 합니다.");
        }
        if (kind == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출 종류(kind)는 필수입니다.");
        }

        Path target = labelingRoot
                .resolve(String.valueOf(videoId))
                .resolve("v" + version)
                .resolve(kind.segment())
                .normalize();

        if (!target.startsWith(labelingRoot)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 산출 경로입니다.");
        }
        return target;
    }

    /** 설정된 labeling_root(정규화된 절대경로). VisibleForTesting. */
    public Path labelingRoot() {
        return labelingRoot;
    }
}
