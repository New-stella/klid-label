package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteRequest;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 외부에서 비식별한 산출물을 <b>기록</b>해 검수 승인 보류를 푼다(API-215).
 *
 * <h3>이 계약은 비식별을 수행하지 않는다</h3>
 * <p>영상 파일 없이 프레임만 가져온 산출물은 저작도구가 비식별할 대상 영상을 가지고 있지 않다. 그래서
 * 비식별은 밖에서 이뤄지고, 여기서는 그 산출물이 어디 있는지를 받아 <b>사실로 기록</b>한다.
 *
 * <h3>★ 확인 없이 기록만 바꾸는 경로를 두지 않는다 (ADR-048 이 명시한 위험)</h3>
 * <p>기록이 곧 승인 보류 해제이므로, 확인 없이 값만 바꿀 수 있으면 <b>처리되지 않은 산출물이 검수
 * 승인을 통과</b>한다. 그래서 이 서비스는
 * <ol>
 *   <li>위치가 <b>허용된 저장소 범위</b> 안인지 판정하고(표기가 아니라 실제로 닿는 자리 기준),</li>
 *   <li>그 자리에 <b>산출물이 실재하는지</b> 확인하고,</li>
 *   <li>그 산출물이 <b>이 영상의 것인지</b>(프레임과 이름이 맞는 파일이 하나라도 있는지) 확인한 뒤에만</li>
 * </ol>
 * 이력 행을 남기고 값을 바꾼다. 셋 중 하나라도 없으면 그 위험이 그대로 실현된다.
 *
 * <h3>★ 폴더 위치만 남기고 끝내지 않는다 — 프레임마다 비식별 이미지 위치를 채운다</h3>
 * <p>프레임의 비식별 위치가 빈 채로 남으면 그 영상의 학습데이터 산출물이 <b>언제나 부분 성공</b>으로
 * 마감되고, 관제는 비식별 이미지가 빠진 산출물을 받는다. 그래서 받은 폴더의 파일을 프레임에 이어
 * 그 위치까지 적재한다.
 *
 * <h3>대응은 파일 이름으로만 한다 — 짐작으로 잇지 않는다</h3>
 * <p>산출물이 준 프레임 이미지의 파일 이름과 <b>같은 이름</b>을 받은 폴더에서 찾아 잇는다. 이름이 맞는
 * 파일이 없는 프레임은 <b>비워 둔 채로 두고 그 수를 응답으로 알린다</b>. 순서나 개수로 추정해 이으면
 * 다른 프레임의 비식별 이미지가 붙고, 붙고 나면 어느 것이 짐작이었는지 구분할 수 없다.
 *
 * <h3>저장 위치는 비식별 서브트리 규약을 따른다</h3>
 * <p>원본 기준경로와 비식별 기준경로가 <b>같은 디렉터리로 설정될 수 있어</b> 어느 벌인지 가리는 것은
 * 그 아래의 접두뿐이다. 규약 밖에 둔 프레임은 비식별 산출물로 인정되지 않아 학습데이터 산출이 거부되고
 * 그 프레임의 이미지도 열리지 않는다. 그래서 받은 폴더를 <b>가리키기만 하지 않고</b> 규약이 정한 자리로
 * 옮겨 놓는다.
 *
 * <h3>왜 이 경로로 들어온 영상만 받는가</h3>
 * <p>이 계약은 <b>이관 경로로 원본이라고 지정해 들어온 영상</b>의 보류를 푸는 수단이지, 다른 경로로
 * 들어온 영상의 비식별 사실을 대신 적는 자리가 아니다. 그쪽 축에는 저작도구가 수행한 비식별 이력이
 * 따로 있고, 여기서 사람이 적은 값이 그것과 섞이면 어느 것이 실제 처리 결과인지 갈린다.
 *
 * @design DOMAIN-017
 * @design API-215
 * @design AC-046
 * @design ERD-031
 * @design ADR-048
 */
@Service
@RequiredArgsConstructor
public class ImportDeidentCompleteService {

    private static final Logger log = LoggerFactory.getLogger(ImportDeidentCompleteService.class);

    /** 산출물 폴더를 훑을 때 읽을 항목 수 상한 — 프레임 수백 장 규모를 넉넉히 덮는다. */
    private static final int ARTIFACT_SCAN_LIMIT = 20_000;

    /**
     * 거부 사유를 <b>한 문구로 통일</b>한다 — 허용 범위 밖·산출물 부재·이 영상의 산출물이 아님을
     * 구분해 알리면 응답이 저장소 상태를 알려주는 오라클이 된다(CWE-209).
     */
    private static final String REJECT_MESSAGE =
            "비식별 산출물을 확인할 수 없거나 이 경로의 대상이 아닌 영상입니다.";

    private final ImportSourcePolicy sourcePolicy;
    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final LsDataSrcRepository srcRepository;
    private final ImportFileStager fileStager;
    private final ImportDeidentCompleteTxService txService;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 비식별 완료를 기록한다.
     *
     * <p>순서가 곧 사양이다 — 판정·확인·대응은 <b>트랜잭션 밖</b>에서 끝나고, 파일을 옮긴 뒤에야 한
     * 트랜잭션으로 영속한다. 영속이 깨지면 이번에 옮긴 파일만 걷어낸다.
     *
     * @throws CustomException 이관 경로 영상이 아니거나 산출물 확인 실패(INVALID_INPUT) /
     *                         영상·상태 부재(NOT_FOUND) / 이미 완료로 기록됨(CONFLICT)
     */
    public DeidentCompleteResponse record(Long rawSn, DeidentCompleteRequest request, String actorId) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (!LsDataRaw.SRC_TYPE_IMPORTED.equals(raw.getSrcType())) {
            // 뒤에 달라지지 않는 조건이라 다시 시도해도 결과가 같다 — 일시 조건(412)이 아니라 400 이다.
            throw new CustomException(ErrorCode.INVALID_INPUT, REJECT_MESSAGE);
        }
        LsRawDataStatus status = statusRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (status.isDeidentCompleted()) {
            // 풀 보류가 없고, 두 번 기록하면 어느 산출물이 그 영상의 비식별 결과인지 이력에서 갈린다.
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 완료로 기록된 영상입니다.");
        }

        Path folder = sourcePolicy.verifyFolder(request.deidentifiedFolderPath());
        Map<String, Path> artifacts = indexUsableFiles(folder);
        if (artifacts.isEmpty()) {
            // ★ fail-closed 지점 ① — 산출물이 실재하지 않으면 아무것도 기록하지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, REJECT_MESSAGE);
        }

        Matching matching = matchByFileName(rawSn, raw.getVmsClipId(), artifacts);
        if (matching.links().isEmpty()) {
            // ★ fail-closed 지점 ② — 실재는 하지만 이 영상의 프레임과 이름이 하나도 맞지 않는다.
            //   그 폴더는 이 영상의 산출물이 아니므로 기록하지 않는다(보류도 그대로 남는다).
            log.warn("[Import] deident artifact does not belong to this video rawSn={} frames={}",
                    rawSn, matching.unmatched());
            throw new CustomException(ErrorCode.INVALID_INPUT, REJECT_MESSAGE);
        }

        List<String> staged = matching.links().stream()
                .map(ImportDeidentCompleteTxService.FrameLink::deidFilePath).toList();
        long procLogSn;
        try {
            for (ImportDeidentCompleteTxService.FrameLink link : matching.links()) {
                fileStager.copy(link.source(), link.deidFilePath());
            }
            procLogSn = txService.record(rawSn, raw.getRawFilePathNm(), folder.toString(),
                    actorId, matching.links());
        } catch (RuntimeException e) {
            // 기록이 서지 않았으면 옮긴 파일도 남기지 않는다 — 아무도 가리키지 않는 비식별 이미지가
            //   저장소에 쌓이면 나중에 그것이 어느 영상의 것인지 알 수 없다.
            fileStager.cleanupQuietly(staged);
            throw e;
        }

        if (matching.unmatched() > 0) {
            // 그만큼의 프레임이 비식별 이미지 없이 남는다 — 산출물은 그 프레임을 건너뛴다.
            log.warn("[Import] deident frames left empty rawSn={} matched={} unmatched={}",
                    rawSn, matching.links().size(), matching.unmatched());
        }
        log.info("[Import] deident complete recorded rawSn={} procLogSn={} matched={} unmatched={}",
                rawSn, procLogSn, matching.links().size(), matching.unmatched());
        return new DeidentCompleteResponse(rawSn, procLogSn, true,
                matching.links().size(), matching.unmatched());
    }

    /** 이름 대응 결과 — 이을 프레임과 비워 두는 프레임 수. */
    private record Matching(List<ImportDeidentCompleteTxService.FrameLink> links, int unmatched) {
    }

    /**
     * 프레임마다 <b>같은 이름</b>의 파일을 찾아 잇는다.
     *
     * <p>이름 비교는 저장할 때와 <b>같은 변환</b>({@link ImportPathPolicy#storedFileName})을 거친 값끼리
     * 한다 — 한쪽만 정제하면 같은 이름인데 대응이 안 되는 프레임이 생긴다.
     *
     * <p>이름이 맞는 파일이 없는 프레임(짝 이미지가 아예 없던 프레임 포함)은 비워 둔 채로 센다. 순서로
     * 메우지 않는다.
     */
    private Matching matchByFileName(long rawSn, String vmsClipId, Map<String, Path> artifacts) {
        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        List<ImportDeidentCompleteTxService.FrameLink> links = new ArrayList<>();
        int unmatched = 0;
        for (LsDataSrc frame : frames) {
            String name = storedNameOf(frame.getSrcFilePathNm());
            Path source = (name == null) ? null : artifacts.get(name);
            if (source == null) {
                unmatched++;
                continue;
            }
            String target;
            try {
                target = ImportPathPolicy.frameFilePath(storageDeidentifiedPath, true, vmsClipId, name);
            } catch (IllegalArgumentException e) {
                // 저장 위치를 만들 수 없는 프레임은 비워 둔다 — 규약 밖에 두면 어차피 인정되지 않는다.
                log.warn("[Import] deident frame target rejected rawSn={} frameNo={}",
                        rawSn, frame.getFrameNo());
                unmatched++;
                continue;
            }
            links.add(new ImportDeidentCompleteTxService.FrameLink(
                    frame.getSrcSn(), source, target));
        }
        return new Matching(List.copyOf(links), unmatched);
    }

    /** 저장된 프레임 경로에서 <b>파일 이름</b>만 떼어 저장 이름 형태로 돌려준다. */
    private static String storedNameOf(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return null;
        }
        return ImportPathPolicy.storedFileName(storedPath);
    }

    /**
     * 폴더 바로 아래의 <b>쓸 수 있는 파일</b>을 이름으로 색인한다(링크·하위 폴더는 담지 않는다).
     *
     * <h3>왜 크기까지 보는가</h3>
     * <p>존재만 보면 <b>빈 파일 하나</b>로도 통과한다. 그러면 아무것도 처리되지 않은 폴더를 가리켜도
     * 승인 보류가 풀린다. 같은 위험을 다루는 저작도구 자체 비식별 경로가 이미 요구하는 강도다.
     *
     * <h3>바로가기는 담지 않는다 (CWE-59/367)</h3>
     * <p>링크를 놓아 두면 허용 범위 밖 파일이 "비식별 산출물"로 인정되고, 그 픽셀이 그대로 비식별 벌로
     * 복사된다.
     */
    private static Map<String, Path> indexUsableFiles(Path folder) {
        Map<String, Path> index = new LinkedHashMap<>();
        try (Stream<Path> entries = Files.list(folder)) {
            entries.limit(ARTIFACT_SCAN_LIMIT).filter(ImportDeidentCompleteService::usable)
                    .forEach(entry -> {
                        String key = ImportPathPolicy.storedFileName(entry.getFileName().toString());
                        if (key != null) {
                            // 정제 결과가 겹치면 먼저 본 것을 남긴다 — 나중 것으로 덮으면 같은 폴더를
                            //   두 번 읽을 때 어느 파일이 붙을지 흔들린다.
                            index.putIfAbsent(key, entry);
                        }
                    });
        } catch (IOException e) {
            log.warn("[Import] deident artifact scan failed cause={}", e.getClass().getSimpleName());
            return Map.of();
        }
        return index;
    }

    /** 바로가기가 아니고, 일반 파일이며, 내용이 비어 있지 않은가. */
    private static boolean usable(Path candidate) {
        if (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return Files.size(candidate) > 0L;
        } catch (IOException e) {
            // 크기를 읽지 못하면 있다고 보지 않는다(fail-closed).
            return false;
        }
    }
}
