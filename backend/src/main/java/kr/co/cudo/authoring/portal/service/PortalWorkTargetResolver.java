package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 포털 작업 대상의 <b>자산 출처를 가르는 단일 판정 지점</b>.
 *
 * <h3>★★ 왜 이 판정이 이 기능의 핵심인가</h3>
 * <p>포털 라벨링 화면은 두 출처를 <b>같은 화면</b>에서 다루고 <b>같은 요청</b>을 보낸다. 화면은 어느
 * 쪽인지 알 필요가 없고 서버가 자산 출처로 판정한다. 그 판정에 따라 <b>읽기와 쓰기의 저장처가
 * 통째로 갈린다</b>.
 *
 * <table>
 *   <caption>자산 출처별 저장처</caption>
 *   <tr><th>출처</th><th>읽기</th><th>쓰기</th></tr>
 *   <tr><td>데이터마트에서 불러온 영상</td>
 *       <td>원본 + 본인 오버레이 <b>병합</b>(본인 작업분 우선), 가린 값에 표시</td>
 *       <td><b>오버레이 표</b></td></tr>
 *   <tr><td>본인이 올린 영상</td>
 *       <td>오버레이를 거치지 않고 그 자산의 원장 그대로</td>
 *       <td>그 자산의 원장 그대로</td></tr>
 * </table>
 *
 * <p>본인 업로드 자산이 오버레이를 쓰지 않는 이유는 <b>가려야 할 남의 원본이 없어서</b>다. 병합할
 * 것이 없으므로 원본을 가렸다는 표시도 서지 않는다.
 *
 * <p>⚠ <b>전부 오버레이에 넣는 구현은 컴파일도 되고 대부분의 시험도 통과한다.</b> 그런데 본인
 * 업로드 자산의 메타가 원장에 남지 않고 오버레이에만 쌓여 <b>이후 경로가 그것을 못 본다</b>
 * (내려받기·산출 문서 조립이 원장을 읽는다). 그래서 이 갈림은 시험으로 못박혀 있다.
 *
 * <h3>★ 403 과 404 를 가르는 축</h3>
 * <p>계약이 둘 다 정의하는데 403 설명이 「실재 여부가 드러나지 않게」다. 두 코드는 <b>축이 다르다</b>.
 * <ul>
 *   <li><b>404</b> — 프레임·영상 <b>행 자체가 없다</b>. 소유와 무관한 사실이라 감출 것이 없다.</li>
 *   <li><b>403</b> — 행은 있으나 <b>본인 작업 대상이 아니다</b>(남의 업로드 자산이거나 데이터마트에
 *       노출되지 않은 영상). 본문은 <b>두 경우에 완전히 같은 문구</b>라 어느 쪽인지 드러나지 않으며,
 *       특히 남의 자산인지 미승인 영상인지 구분되지 않는다.</li>
 * </ul>
 * <p>이 축은 포털 기존 창구가 이미 쓰고 있는 것이다 — 데이터마트 라벨 Load 는 프레임 부재를 404,
 * 미승인 영상을 403 으로 가르고({@code PortalLabelService.loadFrameLabels}), 업로드 축은 부재·타인을
 * 403 한 문구로 통일한다({@code PortalUploadLabelService}). 새 규칙을 만든 것이 아니다.
 *
 * @design API-234
 * @design API-235
 * @design API-236
 * @design API-237
 */
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class PortalWorkTargetResolver {

    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;

    /** 자산 출처 — 저장처를 가르는 축. */
    public enum Origin {
        /** 관제가 구축한 데이터마트에서 불러온 영상. 오버레이에 쌓는다. */
        DATAMART,
        /** 포털 사용자가 직접 올린 본인 자산. 그 자산의 원장에 그대로 쌓는다. */
        PORTAL_UPLOAD
    }

    /**
     * 확정된 작업 대상.
     *
     * @param rawSn  영상 PK
     * @param srcSn  프레임 PK. 영상 축 창구로 진입했으면 {@code null}
     * @param origin 자산 출처
     */
    public record Target(Long rawSn, Long srcSn, Origin origin) {

        public boolean isUpload() {
            return origin == Origin.PORTAL_UPLOAD;
        }
    }

    /**
     * 판정을 통과한 대상과 <b>그 판정이 조회한 프레임 행</b>.
     *
     * @param target 확정된 작업 대상(출처 포함)
     * @param frame  판정에 쓴 프레임 엔티티 — 호출자가 같은 행을 다시 조회하지 않게 넘긴다
     */
    public record FrameTarget(Target target, LsDataSrc frame) {
    }

    /** 프레임 식별자로 진입하는 창구(메타)의 대상 확정. */
    public Target resolveByFrame(Long srcSn, String portalUserNo) {
        // 판정은 한 벌이다 — 프레임 행이 필요 없는 창구도 같은 판정을 거친다.
        return resolveFrame(srcSn, portalUserNo).target();
    }

    /**
     * 프레임 식별자로 진입하는 창구의 대상 확정 + 판정에 쓴 프레임 행 반환.
     *
     * <p>포털 AI 보조 창구가 인가를 통과한 프레임 엔티티(영상 식별자·이미지 경로)를 추론 본체에 넘기려고
     * 쓴다. 판정 규칙은 {@link #resolveByFrame} 와 <b>같은 한 곳</b>이다 — 판정을 복제하지 않고 이 메서드가
     * 판정의 본체이며 {@link #resolveByFrame} 가 여기에 위임한다.
     * @design API-254, API-255, API-257
     */
    public FrameTarget resolveFrame(Long srcSn, String portalUserNo) {
        requireOwner(portalUserNo);
        if (srcSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "srcSn 은 필수입니다.");
        }
        LsDataSrc frame = srcRepository.findById(srcSn).orElseThrow(PortalWorkTargetResolver::notFound);
        // 영상 식별자는 <프레임 행이 가리키는 값>을 쓴다 — 조회한 엔티티에서 다시 꺼내면 같은 값을
        // 두 곳에서 유도하게 된다.
        Long rawSn = frame.getRawSn();
        LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow(PortalWorkTargetResolver::notFound);
        return new FrameTarget(new Target(rawSn, srcSn, originOf(rawSn, raw, portalUserNo)), frame);
    }

    /** 영상 식별자로 진입하는 창구(이벤트 어노테이션)의 대상 확정. */
    public Target resolveByVideo(Long rawSn, String portalUserNo) {
        requireOwner(portalUserNo);
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow(PortalWorkTargetResolver::notFound);
        return new Target(rawSn, null, originOf(rawSn, raw, portalUserNo));
    }

    /**
     * 자산 출처 판정 + 인가.
     *
     * <p>포털 업로드 자산은 <b>출처 판별자와 소유자를 각각</b> 건다 — {@code isPortalUpload()} 가
     * 출처만 보고 소유자는 여기서 따로 확인한다(둘을 한 메서드에 묶지 않는 것이 원장 흡수 규약이다).
     * 소유자가 다르면 부재와 <b>같은 문구</b>의 403 이라 실재 여부가 드러나지 않는다.
     */
    private Origin originOf(Long rawSn, LsDataRaw raw, String portalUserNo) {
        if (raw.isPortalUpload()) {
            if (!portalUserNo.equals(raw.getPortalUserNo())) {
                throw forbidden();
            }
            return Origin.PORTAL_UPLOAD;
        }
        if (!isExposedToDatamart(rawSn)) {
            throw forbidden();
        }
        return Origin.DATAMART;
    }

    /** 데이터마트 노출 조건 — 검수 완료(APPROVED) 영상만 true. 행 부재·타 상태는 false. */
    private boolean isExposedToDatamart(Long rawSn) {
        // 단건도 <일괄 판정>에 위임한다 — 판정 리터럴이 두 곳에 있으면 한쪽만 고쳐진다.
        // ★ 「비어 있지 않다」가 아니라 <그 식별자를 담고 있는가>를 묻는다. 전자로 두면 안전성이
        //   조회 계약(요청한 것만 돌려준다)에만 기대게 되어, 일괄 창구가 다른 식별자를 섞어 돌려주는
        //   순간 <남의 영상이 승인이라는 이유로> 이 진입이 열린다 — 목록 경로에서만 드러나고
        //   단건 403 축은 조용히 통과한다.
        return exposedToDatamart(List.of(rawSn)).contains(rawSn);
    }

    /**
     * 데이터마트 노출 조건 <b>일괄 판정</b> — 목록이 행마다 진입 가능 여부를 물을 때 쓴다.
     * @design API-225
     *
     * <p>「내 작업」 목록은 검수 승인 상태를 <b>등재 조건으로 걸지 않는다</b>(걸면 삭제 예고가 함께
     * 사라진다). 대신 <b>진입 대상 프레임을 비워</b> 화면이 미리 막게 하는데, 그 판정을 목록이
     * 스스로 유도하면 이 클래스와 갈린다 — 그래서 같은 창구를 일괄로 연다.
     *
     * <p>⚠ 이 창구는 <b>화면이 미리 막게 하려는 것</b>이고 {@link #resolveByFrame}·
     * {@link #resolveByVideo} 의 진입 가드를 <b>대신하지 않는다</b>. 가드를 걷어내면 주소를 직접
     * 쳐서 들어갈 수 있다.
     *
     * @return 노출 조건을 만족하는 영상 식별자만. 부재·타 상태는 <b>결과에 없다</b>(fail-closed)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Set<Long> exposedToDatamart(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return Set.of();
        }
        Set<Long> exposed = new LinkedHashSet<>();
        for (LsRawDataStatus status : rawDataStatusRepository.findAllById(rawSns)) {
            if (LsRawDataStatus.STTS_APPROVED.equals(status.getDataSttsCd())) {
                exposed.add(status.getRawDataId());
            }
        }
        return exposed;
    }

    private static void requireOwner(String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.NOT_FOUND, "대상을 찾을 수 없습니다.");
    }

    /**
     * 남의 자산인지 미승인 영상인지 <b>구분되지 않는 한 문구</b>. 문구를 갈라 적으면 응답이
     * 실재 여부·상태를 알려주는 오라클이 된다(CWE-209).
     */
    private static CustomException forbidden() {
        return new CustomException(ErrorCode.FORBIDDEN, "본인 작업 대상이 아니거나 존재하지 않습니다.");
    }
}
