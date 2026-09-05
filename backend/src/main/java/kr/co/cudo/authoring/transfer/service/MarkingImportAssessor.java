package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.parser.MarkingDocument;
import kr.co.cudo.authoring.transfer.parser.MarkingDocumentParser;
import kr.co.cudo.authoring.transfer.parser.MarkingImportWarningCode;
import kr.co.cudo.authoring.transfer.parser.MarkingWarning;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 마킹 문서 하나가 <b>지금 상태로 적재될 수 있는지</b>를 판정한다 — 검사와 적재 대상 선정의 공통 축.
 *
 * <h3>적재를 막는 것과 막지 않는 것을 가른다</h3>
 * <p>막는 것: 문서를 읽을 수 없음 · 짝을 찾지 못함(같은 이름 여럿 포함) · 식별자를 만들 수 없음 ·
 * 시점이 없음 · 식별자가 이미 쓰이고 있음 · 역산값과 실측값이 허용 오차를 넘어 다름.
 * <p>막지 않는 것: 영상에서 속도를 읽지 못함 · 문서에서 속도를 역산할 수 없음.
 * <b>후자를 막으면</b> 영상 판독 도구가 없는 환경에서 묶음 전체가 통째로 잠긴다. 대조할 값이 없는
 * 것과 대조해 보니 다른 것은 <b>다른 사실</b>이다.
 *
 * <h3>속도 대조가 왜 적재를 막는가</h3>
 * <p>마킹은 프레임 번호로 자리를 정하고, 뒤따르는 추출 단계는 그 번호를 시각으로 옮길 때 프레임 재생
 * 속도를 쓴다. 두 값이 어긋난 채 진행하면 <b>이벤트가 없는 엉뚱한 자리의 프레임</b>을 뽑는다.
 * 그 산출물은 겉보기에 정상이라 나중에 사람이 알아채지 못한다(SEQ-030 · AC-1033).
 *
 * <h3>영상 판독은 실패해도 예외로 올리지 않는다</h3>
 * <p>영상 하나를 읽지 못했다고 폴더 검사 전체가 오류로 끝나면, 사람은 무엇이 잘못됐는지 하나도
 * 보지 못한 채 화면만 비게 된다. 읽지 못한 사실을 그 항목의 알림으로 남기고 나머지를 이어 간다.
 *
 * @design DOMAIN-017
 * @design API-216
 * @design AC-1032
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarkingImportAssessor {

    private final MarkingDocumentParser parser;
    private final VideoProbe videoProbe;
    private final VideoRepository videoRepository;
    private final MarkingImportProperties properties;

    /** 훑은 결과를 항목별 판정으로 옮긴다 — 목록 순서는 훑기가 고정한 이름순 그대로다. */
    public List<MarkingCandidate> assess(MarkingFolderScan scan) {
        List<MarkingCandidate> candidates = new ArrayList<>(scan.markingDocuments().size());
        for (Path document : scan.markingDocuments()) {
            candidates.add(assessOne(document, scan));
        }
        return List.copyOf(candidates);
    }

    private MarkingCandidate assessOne(Path document, MarkingFolderScan scan) {
        String markingFileName = fileNameOf(document);
        MarkingDocument parsed = parser.parse(document);
        List<MarkingWarning> warnings = new ArrayList<>(parsed.warnings());

        Path videoPath = null;
        if (parsed.videoFileName() != null) {
            videoPath = scan.uniqueVideo(parsed.videoFileName());
            if (videoPath == null) {
                if (scan.isAmbiguous(parsed.videoFileName())) {
                    warnings.add(MarkingWarning.of(MarkingImportWarningCode.AMBIGUOUS_VIDEO_NAME,
                            "같은 이름의 영상이 둘 이상이라 어느 쪽인지 정할 수 없다."));
                } else {
                    warnings.add(MarkingWarning.of(MarkingImportWarningCode.VIDEO_NOT_FOUND,
                            "마킹 문서가 가리키는 이름의 영상을 훑은 범위 안에서 찾지 못했다."));
                }
            }
        }

        Double probedFps = null;
        Integer frameCount = null;
        if (videoPath != null) {
            VideoProbe.VideoMeta meta = probeQuietly(videoPath);
            if (meta == null || meta.fps() == null) {
                warnings.add(MarkingWarning.of(MarkingImportWarningCode.VIDEO_PROBE_FAILED,
                        "영상에서 프레임 재생 속도를 읽지 못해 대조를 건너뛴다."));
            } else {
                probedFps = meta.fps();
                frameCount = frameCountOf(meta);
            }
        }

        boolean fpsMismatch = isFpsMismatch(parsed.declaredFps(), probedFps);
        if (fpsMismatch) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.FPS_MISMATCH,
                    "마킹 문서에서 역산한 프레임 재생 속도가 영상에서 읽은 값과 허용 오차를 넘어 다르다."));
        }

        boolean duplicate = false;
        if (parsed.clipId() != null) {
            duplicate = videoRepository.findByVmsClipId(parsed.clipId()).isPresent();
            if (duplicate) {
                warnings.add(MarkingWarning.of(MarkingImportWarningCode.DUPLICATE_CLIP_ID,
                        "같은 영상 식별자가 이미 쓰이고 있어 이 항목은 건너뛴다."));
            }
        }

        // 적재 대상이 되면 이 두 경로가 원장 행에 그대로 담긴다. 폭을 넘으면 행을 만들 수 없는데,
        // 그것을 적재 시점에 처음 알면 <작업을 만드는 것 자체>가 통째로 실패한다. 여기서 가른다.
        boolean pathTooLong = isTooLong(document) || (videoPath != null && isTooLong(videoPath));
        if (pathTooLong) {
            warnings.add(MarkingWarning.of(MarkingImportWarningCode.PATH_TOO_LONG,
                    "마킹 문서나 영상의 위치가 담을 수 있는 길이를 넘어 적재할 수 없다."));
        }

        boolean importable = parsed.hasUsableContent()
                && videoPath != null
                && !fpsMismatch
                && !duplicate
                && !pathTooLong;

        return new MarkingCandidate(document, markingFileName, videoPath,
                parsed.videoFileName(), parsed.clipId(), parsed.segmentCount(), parsed.markCount(),
                parsed.declaredFps(), probedFps, frameCount, importable, warnings);
    }

    /**
     * 역산값과 실측값이 허용 오차를 넘어 다른가.
     *
     * <p>둘 중 하나라도 없으면 <b>대조하지 않는다</b>(거짓). 없는 값을 0으로 두고 비교하면 대조할 수
     * 없는 상황이 언제나 「크게 다름」으로 판정되어 정상 묶음이 전건 막힌다.
     */
    public boolean isFpsMismatch(Double declaredFps, Double probedFps) {
        if (declaredFps == null || probedFps == null) {
            return false;
        }
        return Math.abs(declaredFps - probedFps) > properties.effectiveFpsTolerance();
    }

    /**
     * 영상 판독 — 실패를 삼키고 {@code null} 로 돌린다.
     *
     * <p>판독기는 외부 실행 파일에 기대므로 없는 환경에서는 언제나 예외가 난다. 그 예외가 올라가면
     * 폴더 검사 전체가 오류로 끝나 사람이 아무것도 보지 못한다.
     */
    private VideoProbe.VideoMeta probeQuietly(Path video) {
        try {
            return videoProbe.probe(video);
        } catch (RuntimeException e) {
            // CWE-209 — 경로·원인 메시지를 응답에 담지 않는다. 운영 로그에는 종류만 남긴다.
            log.warn("[MarkingImport] video probe failed cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 전체 프레임 수 — 길이와 속도에서 <b>도출</b>한다.
     *
     * <p>판독기가 프레임 수를 직접 주지 않는다(컨테이너에 그 값이 없는 경우가 흔하다). 도출값이라
     * 정확한 수가 아니라 <b>규모</b>를 보여 주는 값이며, 사람이 「시점 번호가 영상 길이 안에 있는가」를
     * 가늠하는 데 쓴다. 도출에 필요한 값이 없으면 지어내지 않고 비운다.
     */
    private static Integer frameCountOf(VideoProbe.VideoMeta meta) {
        if (meta.durationMs() == null || meta.fps() == null || meta.fps() <= 0) {
            return null;
        }
        double frames = meta.durationMs() / 1000.0 * meta.fps();
        if (!Double.isFinite(frames) || frames < 0 || frames > Integer.MAX_VALUE) {
            return null;
        }
        return (int) Math.round(frames);
    }

    /** 원장의 경로 컬럼 폭을 넘는가 — 넘으면 그 항목은 행으로 남길 수 없다. */
    private static boolean isTooLong(Path path) {
        return path.toString().length() > LsEblcUldJobArtcl.FILE_PATH_NM_MAX;
    }

    private static String fileNameOf(Path path) {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }
}
