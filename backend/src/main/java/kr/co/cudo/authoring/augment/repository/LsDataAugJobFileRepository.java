package kr.co.cudo.authoring.augment.repository;

import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.common.datasource.ControlRepo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 증강 위탁 파일 매핑 리포지토리 (LS_DATA_AUG_JOB_FILE, V141).
 *
 * <p>수신부는 job 단위로(콜백 1건 = job 1건) 결과 경로를 되붙이고, 프레임 생성부는 증강 1건
 * 전체를 <b>위탁 순서 그대로</b> 복원해 읽는다.
 */
@ControlRepo
public interface LsDataAugJobFileRepository extends JpaRepository<LsDataAugJobFile, Long> {

    /** job 1건의 위탁 항목 — {@code results[]} 순서와 1:1 대응시키기 위해 FILE_SEQ 오름차순. */
    List<LsDataAugJobFile> findByAugJobSnOrderByFileSeqAsc(Long augJobSn);

    /**
     * 증강 1건(여러 job)의 위탁 항목 전량을 <b>전체 위탁 순서</b>로 복원한다.
     * 정렬 키는 (job 분할 순서 {@code JOB_SEQ}, job 내 입력 순서 {@code FILE_SEQ}) 다.
     */
    @Query("select f from LsDataAugJobFile f, LsDataAugJob j "
            + "where f.augJobSn = j.augJobSn and j.dataAugSn = :dataAugSn "
            + "order by j.jobSeq asc, f.fileSeq asc")
    List<LsDataAugJobFile> findByDataAugSnOrderByJobAndFileSeq(@Param("dataAugSn") Long dataAugSn);
}
