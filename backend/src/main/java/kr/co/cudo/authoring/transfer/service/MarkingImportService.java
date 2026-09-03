package kr.co.cudo.authoring.transfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.dto.MarkingImportCreateRequest;
import kr.co.cudo.authoring.transfer.dto.MarkingImportCreateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 마킹 산출물 일괄 적재 <b>작업을 등록</b>하고 곧바로 반환한다 (API-217).
 *
 * <h3>왜 곧바로 반환하는가</h3>
 * <p>백 건의 파일 복사와 적재는 한 요청이 기다릴 수 있는 시간을 넘는다. 요청 안에서 다 하면 화면은
 * 언제나 시간 초과로 끊기고, 서버는 뒤에서 계속 돌며, 사람은 같은 버튼을 다시 누른다. 그래서 요청
 * 안에서는 <b>무엇을 할지 원장에 적어 두는 것</b>까지만 하고 실행은 일꾼에게 넘긴다.
 *
 * <h3>대상은 검사와 <b>같은 판정기</b>가 고른다</h3>
 * <p>사람이 본 목록과 실제로 적재되는 목록이 갈리지 않게, 여기서도 폴더를 다시 훑어 같은 판정기를
 * 돌린다. 요청이 이름 목록을 함께 보내면 그 안에서 <b>다시 판정</b>한다 — 검사와 적재 사이에 폴더가
 * 바뀌었을 수 있고, 이름만 믿으면 그 사이 중복이 된 항목이 그대로 들어간다.
 *
 * <h3>일꾼을 띄우지 못해도 유실이 아니다</h3>
 * <p>일꾼 자리가 꽉 차 거부되면 이 요청은 그것을 <b>지연</b>으로 다룬다. 할 일은 이미 원장에 행으로
 * 남아 있고, 되돌리기 잡이 다음 순번에 그 작업을 집어 이어 처리한다. 조용히 버리는 것과 다르다.
 *
 * @design DOMAIN-017
 * @design ADR-053
 * @design API-217
 * @design AC-1032
 * @design SEQ-030
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingImportService {

    private final ImportSourcePolicy sourcePolicy;
    private final MarkingFolderScanner folderScanner;
    private final MarkingImportAssessor assessor;
    private final MarkingImportJobTxService jobTxService;
    private final MarkingImportWorkerDispatcher dispatcher;
    private final ImportHistoryTxService historyTxService;
    private final MarkingImportProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 작업을 등록한다.
     *
     * @param actorId 이 작업을 실행한 사람
     * @throws CustomException 경로 형식·허용 범위 위반(INVALID_INPUT) / 폴더 부재(NOT_FOUND)
     */
    public MarkingImportCreateResponse create(MarkingImportCreateRequest request, String actorId) {
        Path folder = sourcePolicy.verifyFolder(request.folderPath());
        MarkingFolderScan scan = folderScanner.scan(folder);
        List<MarkingCandidate> candidates = assessor.assess(scan);

        Set<String> selected = toSelection(request.targets());
        List<MarkingImportJobTxService.ItemSeed> seeds = candidates.stream()
                .filter(MarkingCandidate::importable)
                .filter(c -> selected == null || selected.contains(c.markingFileName()))
                .map(c -> new MarkingImportJobTxService.ItemSeed(
                        c.markingPath().toString(), c.videoPath().toString()))
                .toList();

        Long historySn = historyTxService.start(
                request.folderPath(), folderNameOf(folder), null, actorId);
        String dmndCn = serialize(MarkingImportJobMeta.from(request.meta(), historySn));
        long jobSn = jobTxService.openJob(request.folderPath(), dmndCn, actorId, seeds);

        log.info("[MarkingImport] job opened jobSn={} targets={} scannedFiles={} truncated={}",
                jobSn, seeds.size(), scan.scannedFileCount(), scan.truncated());
        dispatcher.dispatch(jobSn, properties.effectiveConcurrency());
        return new MarkingImportCreateResponse(jobSn, seeds.size());
    }

    /**
     * 요청이 고른 이름 집합 — 비어 있으면 {@code null}(적재할 수 있는 항목 전부).
     *
     * <p>「빈 목록」과 「지정하지 않음」을 같게 다룬다. 계약이 「비우면 전부」라고 정했는데 빈 배열만
     * 다르게 읽으면, 화면이 아무것도 고르지 않은 상태를 빈 배열로 보낼 때 <b>0건짜리 작업</b>이 생겨
     * 사람은 왜 아무것도 안 들어왔는지 알 수 없다.
     */
    private static Set<String> toSelection(List<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        return new HashSet<>(targets);
    }

    private String serialize(MarkingImportJobMeta meta) {
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            // 지정값을 담아 두지 못하면 재기동 이후 이어 처리할 수 없다 — 작업을 만들지 않는다.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "요청을 처리하지 못했습니다.");
        }
    }

    private static String folderNameOf(Path folder) {
        Path name = folder.getFileName();
        return name == null ? null : name.toString();
    }
}
