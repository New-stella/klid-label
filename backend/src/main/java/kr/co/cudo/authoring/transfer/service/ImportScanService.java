package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.dto.ImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.ImportScanResponse;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.parser.ImportWarningCode;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import kr.co.cudo.authoring.transfer.repository.LsOtsdCtgryMpngRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 산출물 폴더를 훑어 <b>무엇이 몇 건 들어오는지</b>를 돌려준다 — 적재 전 미리보기 단계.
 *
 * <h3>아무것도 저장하지 않는다 (AC-041)</h3>
 * <p>이 서비스에는 저장 통로가 없다. 리포지토리는 <b>조회 메서드만</b> 쓰고, 파일도 읽기만 한다.
 * 사람이 확인하고 실행하는 동선이 성립하려면 확인 단계가 부작용을 남기지 않아야 한다 — 확인한 뒤에
 * 상태가 이미 달라져 있으면 그 확인은 무엇을 확인한 것인지 알 수 없다.
 *
 * <h3>트랜잭션을 잡지 않는다</h3>
 * <p>이 경로의 비용은 대부분 <b>파일을 열어 파싱하는 시간</b>이다. 그 구간을 트랜잭션 안에 두면 폴더가
 * 큰 산출물 하나가 커넥션을 오래 쥐고, 검사 요청이 몇 건만 겹쳐도 커넥션이 마른다(이 저장소에 커넥션
 * 기아 전례가 있어 프레임 이미지 서빙이 같은 이유로 트랜잭션 밖 I/O 로 분리돼 있다). 그래서 조회는
 * 각자 짧은 트랜잭션으로 끝내고 파싱은 그 밖에서 한다.
 *
 * <h3>훑기에 상한을 둔다 (CWE-770)</h3>
 * <p>경로는 사람이 넣는 값이라 파일이 수십만 개인 디렉터리를 가리킬 수 있다. 상한을 넘으면
 * <b>조용히 자르지 않고</b> 읽기를 그만두고 그 사실을 경고로 알린다 — 잘라 담으면 "덜 들어온 것"과
 * "원래 그만큼인 것"이 구분되지 않는다. 깊이는 상한이 필요 없다: 파서가 <b>폴더 바로 아래만</b>
 * 보고 하위 폴더로 내려가지 않는다.
 *
 * @design DOMAIN-017
 * @design API-205
 * @design AC-041
 * @design AC-047
 */
@Service
@RequiredArgsConstructor
public class ImportScanService {

    private static final Logger log = LoggerFactory.getLogger(ImportScanService.class);

    private final ImportSourcePolicy sourcePolicy;
    private final FirstAnnotationParser parser;
    private final LsOtsdCtgryMpngRepository mappingRepository;
    private final CategoryMatchSuggester suggester;
    private final VideoRepository videoRepository;

    /**
     * 폴더 하나를 훑을 때 읽을 항목 수 상한.
     *
     * <p>설계에 근거 값이 없어 설정으로 뽑아 둔다 — 코드에 박으면 산출물 규모가 커졌을 때 배포를
     * 다시 해야 하고, 근거 없는 숫자가 사양처럼 굳는다.
     */
    @Value("${authoring.import.scan.max-entries:20000}")
    private int maxEntries;

    /**
     * 산출물 폴더를 검사한다.
     *
     * @throws CustomException 경로 형식·허용 범위 위반(INVALID_INPUT) / 폴더 부재(NOT_FOUND)
     */
    public ImportScanResponse scan(ImportScanRequest request) {
        Path folder = sourcePolicy.verifyFolder(request.folderPath());
        if (request.videoPath() != null && !request.videoPath().isBlank()) {
            // 검사 단계에서도 확인한다 — 적재 때 처음 거부하면 사람이 다 확인한 뒤에 막힌다.
            sourcePolicy.verifyVideoFile(request.videoPath());
        }

        if (countEntries(folder) > maxEntries) {
            log.warn("[Import] scan aborted — folder entry limit exceeded folder={}, limit={}",
                    LogSanitizer.sanitize(folder.getFileName() == null ? null
                            : folder.getFileName().toString()), maxEntries);
            return limitExceeded();
        }

        ImportedDataset dataset = parse(folder);

        List<ImportScanResponse.Warning> warnings = new ArrayList<>(
                dataset.warnings().stream()
                        .map(w -> new ImportScanResponse.Warning(w.code(), w.message()))
                        .toList());

        List<ImportScanResponse.UnmappedCategory> unmapped = resolveUnmapped(dataset, warnings);
        Optional<Long> duplicateRawSn = findDuplicate(dataset, warnings);

        int frameCount = dataset.frames().size();
        if (frameCount == 0) {
            warnings.add(new ImportScanResponse.Warning(ImportWarningCode.NO_FRAME_FOUND,
                    "폴더에서 프레임을 하나도 찾지 못했다."));
        }

        boolean identifiable = warnings.stream()
                .noneMatch(w -> ImportWarningCode.UNIDENTIFIABLE_DATASET.equals(w.code()));
        boolean importable = unmapped.isEmpty()
                && duplicateRawSn.isEmpty()
                && identifiable
                && frameCount > 0;

        Integer declared = dataset.info() == null ? null : dataset.info().totalCount();
        return new ImportScanResponse(
                frameCount,
                declared == null ? 0 : declared,
                dataset.shapeCount(),
                dataset.video() == null ? null : dataset.video().fileName(),
                duplicateRawSn.map(ImportScanResponse.Duplicate::new).orElse(null),
                unmapped,
                List.copyOf(warnings),
                importable);
    }

    // ------------------------------------------------------------------ 훑기

    /** 폴더 바로 아래 항목 수 — 상한 + 1 까지만 센다(다 세는 것 자체가 비용이다). */
    private long countEntries(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.limit((long) maxEntries + 1).count();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 폴더를 읽을 수 없습니다.");
        }
    }

    private ImportScanResponse limitExceeded() {
        return new ImportScanResponse(0, 0, 0L, null, null, List.of(),
                List.of(new ImportScanResponse.Warning(ImportWarningCode.SCAN_LIMIT_EXCEEDED,
                        "폴더의 파일 수가 한 번에 훑을 수 있는 상한을 넘어 읽지 않았다.")),
                false);
    }

    private ImportedDataset parse(Path folder) {
        try {
            return parser.parseFolder(folder);
        } catch (UncheckedIOException | IllegalArgumentException e) {
            // CWE-209 — 내부 경로·원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 문서를 읽을 수 없습니다.");
        }
    }

    // ------------------------------------------------------------------ 분류 대응

    /**
     * 아직 대응이 정해지지 않은 분류를 모은다.
     *
     * <p>판정 기준은 <b>지금 쓰는 대응</b>이다 — 해제된 대응은 그 분류를 다시 처음 보는 분류로
     * 되돌린다(AC-043).
     */
    private List<ImportScanResponse.UnmappedCategory> resolveUnmapped(
            ImportedDataset dataset, List<ImportScanResponse.Warning> warnings) {

        Map<String, String> labelCategories = ImportCategoryCollector.labelCategories(dataset);
        Map<String, String> eventCategories = ImportCategoryCollector.eventCategories(dataset);
        if (labelCategories.isEmpty() && eventCategories.isEmpty()) {
            return List.of();
        }

        Set<String> mappedLabels = mappedCodes(LsOtsdCtgryMpng.MPNG_KND_LABEL, labelCategories.keySet());
        Set<String> mappedEvents = mappedCodes(LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE, eventCategories.keySet());

        List<ImportScanResponse.UnmappedCategory> unmapped = new ArrayList<>();
        CategoryMatchSuggester.Snapshot snapshot = null;
        for (Map.Entry<String, String> entry : labelCategories.entrySet()) {
            if (mappedLabels.contains(entry.getKey())) {
                continue;
            }
            if (snapshot == null) {
                snapshot = suggester.snapshot();
            }
            unmapped.add(toUnmapped(LsOtsdCtgryMpng.MPNG_KND_LABEL, entry, snapshot, warnings));
        }
        for (Map.Entry<String, String> entry : eventCategories.entrySet()) {
            if (mappedEvents.contains(entry.getKey())) {
                continue;
            }
            if (snapshot == null) {
                snapshot = suggester.snapshot();
            }
            unmapped.add(toUnmapped(LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE, entry, snapshot, warnings));
        }
        return List.copyOf(unmapped);
    }

    private ImportScanResponse.UnmappedCategory toUnmapped(
            String kind, Map.Entry<String, String> category,
            CategoryMatchSuggester.Snapshot snapshot, List<ImportScanResponse.Warning> warnings) {

        String code = category.getKey();
        String name = category.getValue();
        if (code.length() > LsOtsdCtgryMpng.OTSD_CTGRY_CD_MAX) {
            // 대응 표에 넣을 수 없는 길이다 — 자르면 다른 분류의 자리에 저장된다. 알리고 미확정으로 둔다.
            warnings.add(new ImportScanResponse.Warning(ImportWarningCode.UNMAPPABLE_CATEGORY_CODE,
                    "분류 식별 문자열이 대응 표에 담을 수 있는 길이를 넘어 대응을 만들 수 없다."));
        }
        List<ImportScanResponse.Suggestion> suggestions = suggester
                .suggest(snapshot, kind, name == null ? code : name).stream()
                .map(s -> new ImportScanResponse.Suggestion(s.targetId(), s.targetName()))
                .toList();
        return new ImportScanResponse.UnmappedCategory(kind, code, name, suggestions);
    }

    private Set<String> mappedCodes(String kind, Set<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return mappingRepository
                .findByMpngKndCdAndUseYnAndOtsdCtgryCdIn(kind, LsOtsdCtgryMpng.USE_YES, codes)
                .stream()
                .map(LsOtsdCtgryMpng::getOtsdCtgryCd)
                .collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------ 중복

    /**
     * 이미 가져온 산출물인가 — 이관 식별자로 가린다(AC-044).
     *
     * <p>식별자를 만들 수 없으면 중복 여부를 <b>알 수 없다</b>. 그때 "중복 아님"으로 답하면 적재가
     * 통과한 뒤 유일 제약에서 깨지므로, 경고를 남기고 적재를 막는다.
     */
    private Optional<Long> findDuplicate(ImportedDataset dataset,
                                         List<ImportScanResponse.Warning> warnings) {
        String datasetIdentifier = dataset.info() == null ? null : dataset.info().identifier();
        String vmsClipId;
        try {
            vmsClipId = ImportPathPolicy.vmsClipId(dataset.folderName(), datasetIdentifier);
        } catch (IllegalArgumentException e) {
            warnings.add(new ImportScanResponse.Warning(ImportWarningCode.UNIDENTIFIABLE_DATASET,
                    "폴더 이름과 데이터셋 식별자가 모두 비어 이관 식별자를 만들 수 없다."));
            return Optional.empty();
        }
        return videoRepository.findByVmsClipId(vmsClipId).map(LsDataRaw::getRawSn);
    }
}
