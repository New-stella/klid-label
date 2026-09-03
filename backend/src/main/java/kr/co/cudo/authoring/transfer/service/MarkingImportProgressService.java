package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.dto.MarkingImportProgressResponse;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJob;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobArtclRepository;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 일괄 적재 작업의 <b>진행 상황</b>을 돌려준다 (API-218).
 *
 * <h3>집계는 항목 행에서 센다 — 작업 행의 누계를 그대로 읽지 않는다</h3>
 * <p>누계는 여러 일꾼이 올리는 값이라 <b>올리는 순간에 실패하면 뒤처진다</b>(마감은 걸렸는데 누계만
 * 못 올린 경우). 그 값을 그대로 보여 주면 진행률이 100%에 닿지 않고 멈춘 것처럼 보인다. 실제로 무슨
 * 일이 있었는지는 <b>항목 행</b>이 갖고 있으므로 거기서 센다.
 * <p>누계 컬럼을 없애지 않는 이유는 그것이 원장의 계약이고, 목록 화면처럼 항목을 세지 않는 자리가
 * 쓸 값이기 때문이다.
 *
 * <h3>거르는 것은 목록뿐이다</h3>
 * <p>상태로 거를 수 있지만 <b>위쪽 집계는 언제나 전체 기준</b>이다. 함께 줄면 사람이 보는 진행률이
 * 필터에 따라 달라져 무엇이 참인지 알 수 없다.
 *
 * <h3>목록은 상한까지만 담는다 (CWE-770)</h3>
 * <p>항목은 백 건을 넘을 수 있고 화면은 이 조회를 <b>주기적으로</b> 부른다. 상한에 걸리면 조용히
 * 자르지 않고 일부만 담았다는 사실을 함께 알린다.
 *
 * @design DOMAIN-017
 * @design API-218
 * @design AC-1032
 */
@Service
@RequiredArgsConstructor
public class MarkingImportProgressService {

    private final LsEblcUldJobRepository jobRepository;
    private final LsEblcUldJobArtclRepository artclRepository;
    private final MarkingImportProperties properties;

    /**
     * 진행 상황을 조회한다.
     *
     * @param status 건별 결과를 이 상태인 것만 담는다. 비우면 모두 담는다
     * @throws CustomException 그런 작업이 없을 때(NOT_FOUND)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public MarkingImportProgressResponse getProgress(long jobSn, String status) {
        LsEblcUldJob job = jobRepository.findById(jobSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없습니다."));

        long succeeded = artclRepository.countByEblcUldJobSnAndArtclSttsCd(
                jobSn, LsEblcUldJobArtcl.ARTCL_STTS_SUCCESS);
        long failed = artclRepository.countFailed(jobSn);

        int limit = properties.maxProgressItems();
        List<LsEblcUldJobArtcl> rows;
        long total;
        if (status == null || status.isBlank()) {
            rows = artclRepository.findByEblcUldJobSnOrderByEblcUldJobArtclSnAsc(
                    jobSn, PageRequest.of(0, limit));
            total = artclRepository.countByEblcUldJobSn(jobSn);
        } else {
            rows = artclRepository.findByEblcUldJobSnAndArtclSttsCdOrderByEblcUldJobArtclSnAsc(
                    jobSn, status, PageRequest.of(0, limit));
            total = artclRepository.countByEblcUldJobSnAndArtclSttsCd(jobSn, status);
        }

        List<MarkingImportProgressResponse.Item> items = rows.stream()
                .map(MarkingImportProgressService::toItem)
                .toList();

        return new MarkingImportProgressResponse(
                job.getEblcUldJobSn(),
                job.getJobSttsCd(),
                job.getOrgnlFldrPathNm(),
                job.getTrgtNocs(),
                (int) (succeeded + failed),
                (int) succeeded,
                (int) failed,
                job.getBgngDt(),
                job.getCmptnDt(),
                total > rows.size(),
                items);
    }

    /**
     * 항목 행을 응답으로 옮긴다.
     *
     * <p>돌려주는 것은 <b>파일 이름</b>이지 위치가 아니다. 저장소 안의 절대 경로를 화면으로 내보내면
     * 서버의 내부 구조가 그대로 드러난다(CWE-209). 사람이 되짚는 데 필요한 것은 어느 문서·어느 영상
     * 이었는지이고 그것은 이름으로 충분하다.
     */
    private static MarkingImportProgressResponse.Item toItem(LsEblcUldJobArtcl row) {
        return new MarkingImportProgressResponse.Item(
                fileNameOf(row.getMarkFilePathNm()),
                fileNameOf(row.getVdoFilePathNm()),
                row.getArtclSttsCd(),
                row.getRawSn(),
                row.getFailRsn());
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        Path name = Paths.get(path).getFileName();
        return name == null ? null : name.toString();
    }
}
