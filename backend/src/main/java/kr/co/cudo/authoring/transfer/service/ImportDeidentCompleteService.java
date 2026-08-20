package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteRequest;
import kr.co.cudo.authoring.transfer.dto.DeidentCompleteResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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
 *   <li>그 자리에 <b>산출물이 실재하는지</b> 확인한 뒤에만</li>
 * </ol>
 * 이력 행을 남기고 값을 바꾼다. 두 검사 중 하나라도 없으면 그 위험이 그대로 실현된다.
 *
 * <h3>왜 이 경로로 들어온 영상만 받는가</h3>
 * <p>이 계약은 <b>이관 경로로 원본이라고 지정해 들어온 영상</b>의 보류를 푸는 수단이지, 다른 경로로
 * 들어온 영상의 비식별 사실을 대신 적는 자리가 아니다. 그쪽 축에는 저작도구가 수행한 비식별 이력이
 * 따로 있고, 여기서 사람이 적은 값이 그것과 섞이면 어느 것이 실제 처리 결과인지 갈린다.
 *
 * @design DOMAIN-017
 * @design API-215
 * @design AC-046
 * @design ADR-048
 */
@Service
@RequiredArgsConstructor
public class ImportDeidentCompleteService {

    private static final Logger log = LoggerFactory.getLogger(ImportDeidentCompleteService.class);

    /** 산출물 실재 판정에서 한 번에 훑을 항목 수 상한 — 존재만 보면 되므로 첫 건에서 끝난다. */
    private static final int EXISTENCE_SCAN_LIMIT = 4096;

    private final ImportSourcePolicy sourcePolicy;
    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final LsDeidentProcLogRepository procLogRepository;

    /**
     * 비식별 완료를 기록한다.
     *
     * @throws CustomException 이관 경로 영상이 아니거나 산출물 실재 확인 실패(INVALID_INPUT) /
     *                         영상·상태 부재(NOT_FOUND) / 이미 완료로 기록됨(CONFLICT)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public DeidentCompleteResponse record(Long rawSn, DeidentCompleteRequest request, String actorId) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (!LsDataRaw.SRC_TYPE_IMPORTED.equals(raw.getSrcType())) {
            // 뒤에 달라지지 않는 조건이라 다시 시도해도 결과가 같다 — 일시 조건(412)이 아니라 400 이다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 산출물을 확인할 수 없거나 이 경로의 대상이 아닌 영상입니다.");
        }
        LsRawDataStatus status = statusRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        if (status.isDeidentCompleted()) {
            // 풀 보류가 없고, 두 번 기록하면 어느 산출물이 그 영상의 비식별 결과인지 이력에서 갈린다.
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 완료로 기록된 영상입니다.");
        }

        Path artifact = verifyArtifact(request.deidentifiedFolderPath());

        // 판정이 돌려준 <b>실경로</b>를 적재한다 — 표기 경로를 적으면 나중에 그 표기가 다른 자리를
        //   가리키게 바뀌어도 알 수 없다. 비식별 산출물의 위치는 언제나 이 값을 읽어 쓰며 이름을
        //   조합하거나 추측하지 않는다(ERD-031 비식별 이력 절).
        String artifactPath = artifact.toString();
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                rawSn, null, raw.getRawFilePathNm(), actorId);
        procLog.succeed(artifactPath);
        LsDeidentProcLog savedLog = procLogRepository.save(procLog);

        status.markDeidentCompleted();

        log.info("[Import] deident complete recorded rawSn={} procLogSn={}",
                rawSn, savedLog.getProcLogSn());
        return new DeidentCompleteResponse(rawSn, savedLog.getProcLogSn(), true);
    }

    /**
     * 위치 판정 + <b>산출물 실재 확인</b>.
     *
     * <p>⚠ 이 메서드가 이 계약의 fail-closed 지점이다. 여기를 빼면 "확인 없이 기록만 바꿔 보류를 푸는"
     * 경로가 생겨 처리되지 않은 산출물이 검수 승인을 통과한다(ADR-048 이 명시한 위험).
     *
     * <p>폴더 안의 <b>바로가기는 따라가지 않는다</b> — 링크를 놓아 두면 허용 범위 밖 파일의 존재가
     * 곧 "비식별 산출물이 있다"는 판정이 된다(CWE-59/367). 그리고 <b>내용이 없는 파일</b>은 산출물로
     * 세지 않는다 — 빈 파일 하나로 보류가 풀리면 확인이 사실상 없는 것과 같다.
     *
     * @return 판정에 사용한 실경로
     */
    private Path verifyArtifact(String folderPath) {
        Path folder = sourcePolicy.verifyFolder(folderPath);
        if (!hasUsableArtifact(folder)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 산출물을 확인할 수 없거나 이 경로의 대상이 아닌 영상입니다.");
        }
        return folder;
    }

    /**
     * 폴더 바로 아래에 <b>내용이 있는 일반 파일</b>이 하나라도 있는가(링크·하위 폴더는 세지 않는다).
     *
     * <h3>왜 크기까지 보는가</h3>
     * <p>존재만 보면 <b>빈 파일 하나</b>로도 통과한다. 그러면 아무것도 처리되지 않은 폴더를 가리켜도
     * 승인 보류가 풀린다. 같은 위험을 다루는 저작도구 자체 비식별 경로는 이미 존재와 함께 크기 하한을
     * 요구하고 있어, 같은 위험에 두 갈래의 강도가 달랐다. 여기를 그 강도에 맞춘다.
     *
     * <p>그 이상(프레임 수와의 대응 확인 등)은 하지 않는다 — 무엇을 몇 건 받아야 맞는지는 이 계약에
     * 규정돼 있지 않아, 지어낸 기준으로 정상 산출물을 막게 된다.
     */
    private static boolean hasUsableArtifact(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.limit(EXISTENCE_SCAN_LIMIT).anyMatch(ImportDeidentCompleteService::usable);
        } catch (IOException e) {
            log.warn("[Import] deident artifact scan failed cause={}", e.getClass().getSimpleName());
            return false;
        }
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
