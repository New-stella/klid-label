package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 증강 1건 + 청크 job 전량의 <b>읽기 전용 스냅샷 로더</b> — 진행상태/취소 경로 공용.
 *
 * <h3>왜 별도 빈인가 (커넥션 점유 차단)</h3>
 * <p>진행상태 조회는 <b>외부 HTTP 왕복</b>(상태조회 §4.4, 결과조회 §4.5)을 동반한다. 조회 서비스에
 * {@code @Transactional} 을 걸면 그 트랜잭션이 잡은 커넥션을 외부 왕복 내내 붙잡아, 폴링(화면 수 ×
 * 주기)만으로 커넥션 풀이 마른다 — {@code AugmentJobSubmitService} 가 같은 이유로 트랜잭션
 * 애너테이션을 두지 않는다(그 클래스 주석의 "커넥션 2중 점유 회피" 참조).
 *
 * <p>그래서 DB 접근만 이 빈이 짧은 트랜잭션으로 처리하고 <b>즉시 반납</b>한다. 자기호출이면 프록시가
 * 적용되지 않아 트랜잭션이 통째로 무효가 되므로 반드시 별도 빈이어야 한다.
 */
@Service
@RequiredArgsConstructor
public class AugmentSnapshotLoader {

    private final LsDataAugRepository augRepository;
    private final LsDataAugJobRepository jobRepository;

    /**
     * 증강 1건 스냅샷.
     *
     * @param dataAugSn 증강 PK
     * @param augTypeCd 증강 종류
     * @param augStatus {@code AUG_PROC_STTS_CD}
     * @param jobs      청크 job 전량({@code JOB_SEQ} 오름차순, 빈 목록 가능)
     */
    public record Snapshot(Long dataAugSn, String augTypeCd, String augStatus, List<LsDataAugJob> jobs) {

        public Snapshot {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }
    }

    /**
     * 스냅샷을 읽는다.
     *
     * <h3>404 로 통일한다 (S9 — 열거 완화, CWE-209)</h3>
     * <p>존재하지 않는 id 와 "내가 볼 수 없는 id" 를 다른 코드(404/403)로 회신하면 응답 코드가
     * <b>존재 여부 오라클</b>이 된다. 증강에는 아직 소유자 스코프 개념이 없어
     * ({@code GET /v1/augments} 도 무필터 — <b>기존 정책 상속</b>, 신규 결함이 아니다) 실질 차이가
     * 없지만, 향후 스코프가 생겨도 이 규약이 유지되도록 지금부터 404 로 고정한다.
     *
     * <h3>해상도 파생(RESL_*)은 즉시 차단한다 (S11)</h3>
     * <p>해상도 파생은 <b>외부 위탁 job 이 아예 없는</b> 내부 ffmpeg 생성물이라 job 목록이 항상 비어
     * 있다. 그대로 통과시키면 "PROCESSING 영구 대기" 로 오분류되고 취소는 보낼 대상조차 없다.
     * {@code AugmentReviewService.loadOrThrow} 가 accept/reject 진입을 차단하는 것과 <b>같은 패턴</b>
     * 으로 400 을 준다(CONFLICT 보다 명확한 INVALID_INPUT — 화면 오조작 방어).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Snapshot load(Long dataAugSn) {
        LsDataAug aug = augRepository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "증강 결과를 찾을 수 없습니다."));
        requireExternalAugment(aug);
        return new Snapshot(aug.getDataAugSn(), aug.getAugTypeCd(), aug.getAugProcSttsCd(),
                jobRepository.findByDataAugSnOrderByJobSeqAsc(dataAugSn));
    }

    /** 해상도 파생 차단 — 취소/진행상태 양쪽에서 같은 판정을 쓴다(S11). */
    static void requireExternalAugment(LsDataAug aug) {
        String type = aug.getAugTypeCd();
        if (type != null && type.startsWith(LsDataAug.RESL_PREFIX)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "해상도 파생 결과는 외부 위탁 작업이 아니라 진행상태 조회·취소 대상이 아닙니다.");
        }
    }
}
