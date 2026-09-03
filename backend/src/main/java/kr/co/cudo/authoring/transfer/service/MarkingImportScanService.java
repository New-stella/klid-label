package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.MarkingImportScanResponse;
import kr.co.cudo.authoring.transfer.parser.MarkingImportWarningCode;
import kr.co.cudo.authoring.transfer.parser.MarkingWarning;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 마킹 산출물 폴더를 훑어 <b>짝 목록과 판정</b>을 돌려준다 — 적재 전 미리보기 단계(API-216).
 *
 * <h3>아무것도 저장하지 않는다</h3>
 * <p>이 서비스에는 저장 통로가 없다. 원장은 <b>조회만</b> 하고 파일도 읽기만 한다. 사람이 확인하고
 * 실행하는 동선이 성립하려면 확인 단계가 부작용을 남기지 않아야 한다 — 확인한 뒤에 상태가 이미
 * 달라져 있으면 그 확인은 무엇을 확인한 것인지 알 수 없다. 같은 요청을 여러 번 보내도 결과가 같다.
 *
 * <h3>트랜잭션을 잡지 않는다</h3>
 * <p>이 경로의 비용은 대부분 <b>파일을 열어 읽고 영상을 판독하는 시간</b>이다. 그 구간을 트랜잭션
 * 안에 두면 폴더가 큰 묶음 하나가 커넥션을 오래 쥐고, 검사 요청이 몇 건만 겹쳐도 커넥션이 마른다
 * (이 저장소에 커넥션 기아 전례가 있다). 조회는 각자 짧은 트랜잭션으로 끝내고 읽기는 그 밖에서 한다.
 *
 * <h3>어느 문서도 가리키지 않은 영상을 함께 알린다</h3>
 * <p>그 영상은 적재 대상이 아니지만, 몇 건인지 알려야 사람이 <b>묶음이 온전한지</b> 판단할 수 있다.
 * 마킹 문서가 통째로 빠진 채 영상만 들어온 묶음은 이 수치로만 드러난다.
 *
 * @design DOMAIN-017
 * @design API-216
 * @design AC-1032
 * @design AC-1033
 * @design SEQ-030
 */
@Service
@RequiredArgsConstructor
public class MarkingImportScanService {

    private final ImportSourcePolicy sourcePolicy;
    private final MarkingFolderScanner folderScanner;
    private final MarkingImportAssessor assessor;
    private final MarkingImportProperties properties;

    /**
     * 마킹 산출물 폴더를 검사한다.
     *
     * @throws kr.co.cudo.authoring.common.exception.CustomException 경로 형식·허용 범위 위반
     *                                                              (INVALID_INPUT) / 폴더 부재(NOT_FOUND)
     */
    public MarkingImportScanResponse scan(MarkingImportScanRequest request) {
        Path folder = sourcePolicy.verifyFolder(request.folderPath());
        MarkingFolderScan scan = folderScanner.scan(folder);
        List<MarkingCandidate> candidates = assessor.assess(scan);
        return toResponse(scan, candidates);
    }

    /**
     * 판정 결과를 응답으로 옮긴다 — 적재 경로도 같은 훑기·판정을 쓰므로 변환만 여기 둔다.
     */
    MarkingImportScanResponse toResponse(MarkingFolderScan scan, List<MarkingCandidate> candidates) {
        List<MarkingImportScanResponse.Item> items = new ArrayList<>(candidates.size());
        int matched = 0;
        int importable = 0;
        Set<String> referenced = new LinkedHashSet<>();
        for (MarkingCandidate candidate : candidates) {
            if (candidate.videoFound()) {
                matched++;
            }
            if (candidate.importable()) {
                importable++;
            }
            if (candidate.videoFileName() != null) {
                referenced.add(candidate.videoFileName());
            }
            items.add(new MarkingImportScanResponse.Item(
                    candidate.markingFileName(),
                    candidate.clipId(),
                    candidate.videoFileName(),
                    candidate.videoFound(),
                    candidate.segmentCount(),
                    candidate.markCount(),
                    candidate.declaredFps(),
                    candidate.probedFps(),
                    candidate.videoFrameCount(),
                    candidate.importable(),
                    toWarnings(candidate.warnings())));
        }

        // 어느 문서도 가리키지 않은 영상 — 같은 이름이 여럿이어도 <이름> 하나로 센다. 사람이 보는 것은
        // 「이 이름이 아무 문서에도 안 걸렸다」는 사실이고, 그 이름의 파일이 몇 개인지는 다른 축이다.
        List<String> unmatched = scan.videosByName().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .toList();

        List<MarkingImportScanResponse.Warning> warnings = new ArrayList<>();
        if (scan.truncated()) {
            warnings.add(warning(MarkingImportWarningCode.SCAN_LIMIT_EXCEEDED,
                    "훑기 상한에 걸려 폴더의 일부만 살펴봤다."));
        }
        if (scan.symlinkSkipped() > 0) {
            warnings.add(warning(MarkingImportWarningCode.SYMBOLIC_LINK_SKIPPED,
                    "폴더 안의 바로가기 " + scan.symlinkSkipped() + "건을 따라가지 않고 건너뛰었다."));
        }
        if (scan.unreadableCount() > 0) {
            warnings.add(warning(MarkingImportWarningCode.SCAN_LIMIT_EXCEEDED,
                    "열 수 없어 살펴보지 못한 폴더가 " + scan.unreadableCount() + "건 있다."));
        }
        if (!unmatched.isEmpty()) {
            warnings.add(warning(MarkingImportWarningCode.UNMATCHED_VIDEO_PRESENT,
                    "어느 마킹 문서도 가리키지 않은 영상이 " + unmatched.size() + "건 있다."));
        }

        // 이름 목록은 상한까지만 담되 <전체 수>는 그대로 알린다 — 담긴 수를 세어 판단하면
        // 상한에 걸린 순간부터 사람이 보는 미참조 영상 수가 실제보다 적어진다.
        List<String> unmatchedNames = unmatched.size() <= properties.maxUnmatchedNames()
                ? unmatched
                : unmatched.subList(0, properties.maxUnmatchedNames());

        return new MarkingImportScanResponse(
                List.copyOf(items),
                scan.scannedFileCount(),
                matched,
                importable,
                unmatched.size(),
                List.copyOf(unmatchedNames),
                scan.truncated(),
                List.copyOf(warnings));
    }

    private static List<MarkingImportScanResponse.Warning> toWarnings(List<MarkingWarning> source) {
        return source.stream()
                .map(w -> new MarkingImportScanResponse.Warning(w.code(), w.message()))
                .toList();
    }

    private static MarkingImportScanResponse.Warning warning(String code, String message) {
        return new MarkingImportScanResponse.Warning(code, message);
    }
}
