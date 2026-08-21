package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 비식별 완료 기록을 <b>한 트랜잭션에</b> 영속한다 — 처리 이력·프레임 비식별 경로·승인 보류 해제(API-215).
 *
 * <h3>왜 별도 빈인가</h3>
 * <p>이 계약은 산출물 파일을 프레임마다 저작도구 저장소로 옮긴다. 영상 한 건에 프레임이 수백 장이라
 * 그 복사를 트랜잭션 안에서 하면 커넥션을 오래 쥔다(이 저장소에 커넥션 기아 전례가 있어 이관 적재도
 * 같은 이유로 복사를 트랜잭션 밖에 두었다). 같은 빈 안에서 나누면 자기호출이라 프록시를 지나지 않아
 * 트랜잭션 표시가 <b>실제로는 아무 일도 하지 않으므로</b> 빈을 나눈다.
 *
 * <h3>세 가지가 함께 성립해야 한다</h3>
 * <p>처리 이력만 남고 프레임 경로가 비면 그 영상의 학습데이터 산출물이 <b>언제나 부분 성공</b>으로
 * 마감되고, 프레임 경로만 채워지고 보류가 남으면 승인이 열리지 않는다. 하나라도 깨지면 통째로 되돌린다.
 *
 * @design DOMAIN-017
 * @design API-215
 * @design AC-046
 * @design ERD-031
 */
@Service
@RequiredArgsConstructor
public class ImportDeidentCompleteTxService {

    private static final Logger log = LoggerFactory.getLogger(ImportDeidentCompleteTxService.class);

    private final LsRawDataStatusRepository statusRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final LsDataSrcRepository srcRepository;

    /**
     * 프레임 1건과 그 프레임에 이을 비식별 이미지의 저장 위치.
     *
     * @param srcSn        프레임 식별번호
     * @param source       받은 폴더에서 찾은 <b>실경로</b>(표기 경로를 다시 만들지 않는다)
     * @param deidFilePath 비식별 서브트리 규약이 정한 저장 위치
     */
    public record FrameLink(long srcSn, Path source, String deidFilePath) {
    }

    /**
     * 기록을 영속한다.
     *
     * @param artifactPath 판정이 돌려준 산출물 폴더 <b>실경로</b> — 처리 이력에 적재된다
     * @param links        이름이 맞아 이을 프레임 목록(비어 있으면 호출부가 이미 거부했다)
     * @return 이번에 남긴 비식별 처리 이력의 식별번호
     * @throws CustomException 상태 부재(NOT_FOUND) / 이미 완료로 기록됨(CONFLICT)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public long record(long rawSn, String rawFilePathNm, String artifactPath, String actorId,
                       List<FrameLink> links) {
        LsRawDataStatus status = statusRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        // 진입부 판정과 이 트랜잭션 사이에 다른 요청이 먼저 기록했을 수 있다 — 두 번 기록하면 어느
        //   산출물이 그 영상의 비식별 결과인지 이력에서 갈리므로 여기서 한 번 더 본다.
        if (status.isDeidentCompleted()) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 완료로 기록된 영상입니다.");
        }

        // 비식별 산출물의 위치는 <b>적재값</b>을 읽어 쓴다 — 이름을 조합하거나 추측하지 않는다
        //   (외부 비식별 처리가 붙이는 파일 이름은 처리 주체마다 다르다. ERD-031 비식별 이력 절).
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, rawFilePathNm, actorId);
        procLog.succeed(artifactPath);
        LsDeidentProcLog savedLog = procLogRepository.save(procLog);

        attachFramePaths(links);
        status.markDeidentCompleted();
        return savedLog.getProcLogSn();
    }

    /**
     * 프레임마다 비식별 이미지 위치를 채운다.
     *
     * <p>프레임 행이 사라졌으면 조용히 건너뛴다 — 그 한 건 때문에 기록 전체를 되돌리면 승인 보류가
     * 풀리지 않아 그 영상이 영영 승인되지 않는다.
     */
    private void attachFramePaths(List<FrameLink> links) {
        Map<Long, String> pathBySrcSn = new HashMap<>();
        for (FrameLink link : links) {
            pathBySrcSn.put(link.srcSn(), link.deidFilePath());
        }
        List<LsDataSrc> frames = srcRepository.findAllById(pathBySrcSn.keySet());
        for (LsDataSrc frame : frames) {
            frame.attachDeidPath(pathBySrcSn.get(frame.getSrcSn()));
        }
        if (frames.size() != pathBySrcSn.size()) {
            log.warn("[Import] deident frame link partially applied requested={} applied={}",
                    pathBySrcSn.size(), frames.size());
        }
    }
}
