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

    /** 프레임 식별자로 진입하는 창구(메타)의 대상 확정. */
    public Target resolveByFrame(Long srcSn, String portalUserNo) {
        requireOwner(portalUserNo);
        if (srcSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "srcSn 은 필수입니다.");
        }
        LsDataSrc frame = srcRepository.findById(srcSn).orElseThrow(PortalWorkTargetResolver::notFound);
        // 영상 식별자는 <프레임 행이 가리키는 값>을 쓴다 — 조회한 엔티티에서 다시 꺼내면 같은 값을
        // 두 곳에서 유도하게 된다.
        Long rawSn = frame.getRawSn();
        LsDataRaw raw = videoRepository.findById(rawSn).orElseThrow(PortalWorkTargetResolver::notFound);
        return new Target(rawSn, srcSn, originOf(rawSn, raw, portalUserNo));
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
        return rawDataStatusRepository.findById(rawSn)
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
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
