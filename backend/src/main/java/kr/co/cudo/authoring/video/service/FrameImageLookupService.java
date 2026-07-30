package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프레임 이미지 서빙의 <b>DB 조회·인가·게이트 전담</b> 빈 — 트랜잭션 경계를 여기서 <b>끝낸다</b>.
 *
 * <h3>왜 별도 빈인가 (W3 — 커넥션 기아 방어)</h3>
 * <p>{@link FrameImageService} 는 고빈도 서빙 경로다(라벨링 캔버스는 프레임마다, 썸네일 스트립은
 * 썸네일마다 호출). 서비스에 클래스 레벨 {@code @Transactional(readOnly=true)} 가 걸려 있으면
 * {@code open-in-view: false} 형상에서도 <b>NAS 파일 I/O</b>({@code toRealPath}/{@code readAttributes}/
 * {@code newInputStream}) 가 DB 커넥션을 <b>쥔 채</b> 수행된다. 이 프로젝트에는 커넥션 기아 교착
 * 실사고 이력이 있어 "조회는 트랜잭션 안, I/O 는 트랜잭션 밖" 을 구조로 못박는다.
 *
 * <h3>왜 같은 클래스의 private 메서드가 아닌가 (self-invocation 금지)</h3>
 * <p>같은 빈 안에서 {@code this.loadXxx()} 를 호출하면 <b>프록시를 타지 않아</b>
 * {@code @Transactional} 이 통째로 유실된다 — 이 프로젝트에서 실제로 배치 전면 불통을 낸 결함
 * 패턴이다(PR #60). 따라서 조회 단계를 <b>별도 빈</b>으로 분리해 호출이 항상 프록시를 경유하게 한다.
 *
 * <h3>반환 계약</h3>
 * <p>엔티티가 아니라 <b>값 레코드</b>({@link FrameSpec})를 돌려준다. 트랜잭션 밖에서 엔티티를 만지면
 * (지연 로딩·detached 갱신) 다시 커넥션이 필요해지므로, 서빙에 필요한 값만 트랜잭션 안에서 뽑는다.
 *
 * <h3>순서 고정 (보안)</h3>
 * <p>①인가({@link LabelAccessGuard#verifyAndGet} — CWE-639 IDOR) → ②비식별 신고 구간 게이트
 * (412, CWE-359) → ③값 추출. 게이트를 인가보다 앞에 두면 미배정 WORKER 가 응답 코드로 프레임 존재
 * 여부를 탐색할 수 있으므로 인가가 항상 먼저다.
 */
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class FrameImageLookupService {

    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    /**
     * 인가 + 비식별 신고 구간 게이트 단일 원천. 판정 로직을 복제하지 않고 이 빈만 호출한다
     * (배선 누락이 과거 결함의 원인이었다).
     */
    private final LabelAccessGuard accessGuard;

    /**
     * 서빙 판정에 필요한 값만 담은 스냅샷 — 트랜잭션 밖으로 나가는 유일한 형태.
     *
     * @param rawSn            영상 PK
     * @param frameNo          프레임 번호(로그·식별자용)
     * @param srcFilePath      원본 프레임 경로({@code SRC_FILE_PATH_NM}) — 파생 프레임은 null
     * @param deidFilePath     비식별 프레임 경로({@code DE_IDNTF_SRC_FILE_PATH_NM})
     * @param needsDeidentify  PRVC/PSDO 여부 — true 면 비식별 경로 부재 시 원본 폴백 금지
     */
    public record FrameSpec(Long rawSn, Long frameNo, String srcFilePath, String deidFilePath,
                            boolean needsDeidentify) {
    }

    /**
     * {@code (rawSn, frameNo)} 키 조회 — {@code GET /v1/videos/{rawSn}/frames/{frameNo}/image} 용.
     * <p>인가는 호출 측({@code VideoController.verifyRawAccess})이 이미 수행했고, 여기의 게이트는
     * 그 뒤의 프리컨디션이라 인가를 대체하지 않는다.
     */
    public FrameSpec byRawSnAndFrameNo(Long rawSn, Integer frameNo) {
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        accessGuard.requireNotUnderDeidentReport(rawSn);
        LsDataSrc src = srcRepository.findByRawSnAndFrameNo(rawSn, frameNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        return toSpec(raw, src);
    }

    /**
     * 프레임 PK({@code SRC_SN}) 키 조회 — {@code GET /v1/frames/{srcSn}/image} 용.
     * 인가(IDOR) → 게이트(412) → 영상 조회 순서를 지킨다.
     */
    public FrameSpec bySrcSn(Long srcSn, TokenClaims actor) {
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        accessGuard.requireNotUnderDeidentReport(src.getRawSn());
        LsDataRaw raw = videoRepository.findById(src.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return toSpec(raw, src);
    }

    /**
     * 비식별 전용 경로 조회 — {@code GET /v1/frames/{srcSn}/deid-image} 용.
     * <p>원본 경로({@code SRC_FILE_PATH_NM})를 <b>읽지 않는다</b> — 이 엔드포인트의 계약이
     * "비식별 벌만 서빙(원본 폴백 금지)" 이므로 값 자체를 트랜잭션 밖으로 내보내지 않는다.
     *
     * @return 비식별 경로(없으면 null — 호출측이 404 로 마감)
     */
    public String deidPathBySrcSn(Long srcSn, TokenClaims actor) {
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        accessGuard.requireNotUnderDeidentReport(src.getRawSn());
        return src.getDeidFilePath();
    }

    private static FrameSpec toSpec(LsDataRaw raw, LsDataSrc src) {
        return new FrameSpec(raw.getRawSn(), src.getFrameNo(), src.getSrcFilePathNm(),
                src.getDeidFilePath(), raw.needsDeidentify());
    }
}
