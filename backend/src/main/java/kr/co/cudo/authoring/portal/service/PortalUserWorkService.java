package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.dto.PortalUserWorkResponse;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository.UserWorkRow;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「내 작업」 목록 — 두 축을 한 목록으로 내리고 <b>행마다 자기 축의 보존 규칙</b>으로 만료를 고지한다.
 * @design API-225, DFEAT-055, SCREEN-028, UC-024
 *
 * <h2>★ 이 창구는 데이터마트 카탈로그를 그리지 않는다</h2>
 * <p>데이터마트 영상 전체를 훑어보는 목록은 포털(Host) 소유다. 여기서 데이터마트 축이 싣는 것은
 * <b>본인 저작물이 있는 영상뿐</b>이며, 그 판정은 {@link PortalUserWorkListRepository} 의 합집합
 * 질의가 소유한다. 데이터마트 영상 전체를 돌려주는 다른 창구
 * ({@code PortalLabelService#listDatamartVideos})로 이 목록을 채우면 저작도구가 남의 화면을 대신
 * 그리는 것이 되므로 그렇게 채우지 않는다.
 *
 * <h2>★★ 만료는 <b>한 목록에 두 규칙</b>이 섞여 나오는 것이 정상이다</h2>
 * <table>
 *   <caption>행 출처별 만료 규칙</caption>
 *   <tr><th>행 출처</th><th>기산점</th><th>판정기</th></tr>
 *   <tr><td>데이터마트</td><td>그 사용자의 저작 <b>최초</b> 저장 시각(세 저작물 통틀어 가장 이른 것)</td>
 *       <td>{@link PortalRetentionPolicy.DatamartExpiry}</td></tr>
 *   <tr><td>업로드 자산</td><td>업로드 채널 자체의 규칙(상태별로 갈린다)</td>
 *       <td>{@link PortalRetentionPolicy.UploadExpiry}</td></tr>
 * </table>
 * <p>두 축은 기산점이 다르므로 <b>한 규칙으로 통일하지 말 것</b>(DFEAT-055). 그리고 판정을 여기서
 * 재유도하지 않는다 — 보존 판정의 소유자는 {@link PortalRetentionPolicy} 하나이고, 데이터마트
 * 기산점의 소유자는 {@link PortalUserWorkRepository#findEarliestAuthoredAtByVideo} 하나다.
 *
 * <p>⚠ <b>응답이 싣는 마지막 저장 시각에서 만료를 다시 계산하지 말 것</b> — 축이 다르다. 마지막
 * 저장에서 계산하면 저장을 반복할 때마다 만료가 뒤로 밀려 자동 삭제가 영영 실행되지 않는다.
 *
 * <h2>N+1 을 만들지 않는다</h2>
 * <p>페이지 한 장에 대해 축별로 <b>일괄 조회 1회씩</b>이다 — 데이터마트 기산점 1회, 업로드 자산
 * 조립 1회, 보존기간 설정은 축마다 스냅샷 1회. 행마다 정책·리포지토리를 부르면 페이지 크기(최대
 * 100)만큼 왕복이 난다.
 *
 * <h2>읽기 전용이다</h2>
 * <p>이 창구는 원본도 동결본도 오버레이도 <b>쓰지 않는다</b>. 관제 통지·산출물 재생성도 일으키지 않는다.
 */
@Service
@RequiredArgsConstructor
public class PortalUserWorkService {

    private final PortalUserWorkListRepository workListRepository;
    private final PortalUserWorkRepository userWorkRepository;
    private final PortalUploadAssetRepository assetRepository;
    private final PortalRetentionPolicy retentionPolicy;
    /** 진입 허용 판정의 <b>소유자</b> — 목록이 그 규칙을 재유도하지 않는다. */
    private final PortalWorkTargetResolver targetResolver;

    /**
     * 본인 작업 목록 한 페이지.
     *
     * <p><b>본인 데이터만</b> 반환한다 — 소유자 식별자가 격리 키이고, 두 축의 질의가 각각 그 값을
     * WHERE 에 강제한다(CWE-639). 타인 자산은 어느 축으로도 행이 되지 않는다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Page<PortalUserWorkResponse> listUserWorks(String portalUserNo, Pageable pageable) {
        requireOwner(portalUserNo);

        Page<UserWorkRow> page = workListRepository.findPage(portalUserNo, pageable);
        if (page.getContent().isEmpty()) {
            // 빈 페이지에 집계·설정 조회를 태우지 않는다(빈 IN 절도 피한다).
            return page.map(row -> toResponse(row, Map.of(), Map.of(), Map.of(), Set.of(), null, null));
        }

        List<Long> datamartRawSns = page.getContent().stream()
                .filter(r -> !r.isUpload())
                .map(UserWorkRow::rawSn)
                .toList();
        List<Long> uploadRawSns = page.getContent().stream()
                .filter(UserWorkRow::isUpload)
                .map(UserWorkRow::rawSn)
                .toList();

        // 데이터마트 축 기산점 — 세 저작물을 통틀어 가장 이른 저장 시각(판정 소유자는 저 리포지토리다).
        Map<Long, LocalDateTime> firstAuthoredAt = datamartRawSns.isEmpty()
                ? Map.of()
                : userWorkRepository.findEarliestAuthoredAtByVideo(portalUserNo, datamartRawSns);
        // ★ 데이터마트 행의 진입 가능 여부 — 판정은 이 목록이 아니라 대상 판정기가 소유한다.
        //   업로드 행은 소유자만 보므로(그 판정기 기준) 이 목록의 모든 업로드 행이 진입 가능하다.
        Set<Long> enterableDatamart = datamartRawSns.isEmpty()
                ? Set.of()
                : targetResolver.workableVideos(datamartRawSns);
        // 업로드 축 상태·등록일·상태변경일 — 조립식과 소유자 스코프를 그대로 재사용한다.
        Map<Long, PortalUploadAsset> assets = uploadRawSns.isEmpty()
                ? Map.of()
                : assetRepository.findByOwnerIn(portalUserNo, uploadRawSns);
        // ★★ 보존기간(READY 축) 기준점은 <라벨 마지막 저장일>이며 그 축의 소유자에게서 받는다.
        //   목록의 lastSavedAt 은 메타 편집·어노테이션까지 세도록 넓어졌으므로 <다른 값>이다 —
        //   그것을 보존 입력으로 넘기면 확정되지 않은 사양으로 기산점을 밀어 비가역 삭제가 늦어진다.
        Map<Long, LocalDateTime> lastLabelSavedAt = uploadRawSns.isEmpty()
                ? Map.of()
                : assetRepository.findLastLabelSavedAt(portalUserNo, uploadRawSns);

        PortalRetentionPolicy.DatamartExpiry datamartExpiry =
                datamartRawSns.isEmpty() ? null : retentionPolicy.datamartExpiry();
        PortalRetentionPolicy.UploadExpiry uploadExpiry =
                uploadRawSns.isEmpty() ? null : retentionPolicy.uploadExpiry();

        return page.map(row -> toResponse(row, firstAuthoredAt, assets, lastLabelSavedAt,
                enterableDatamart, datamartExpiry, uploadExpiry));
    }

    /**
     * 한 행을 응답으로 옮기며 <b>그 행의 축에 맞는</b> 만료 규칙만 적용한다.
     *
     * <h3>★★ 표시축과 보존 입력축은 <b>다른 값</b>이다 — 합치지 말 것</h3>
     * <p>{@code row.lastSavedAt()} 은 저작 편집 셋(라벨 · 포털 메타 편집 · 이벤트 어노테이션)을 세는
     * <b>표시·정렬·내려받기 판정</b> 값이고, 업로드 만료의 기준점은 <b>라벨 마지막 저장일</b>이라
     * 소유자({@code PortalUploadAssetRepository})에게 따로 받는다. 라벨 없이 메타만 고친 자산에서
     * 두 값이 실제로 갈리며 <b>그것이 정상</b>이다 — 전자를 보존 입력으로 넘기면 확정되지 않은 사양
     * (「업로드는 늦은 쪽」의 범위)을 넓혀 비가역 삭제 시점을 밀게 된다(DFEAT-055).
     *
     * <h3>★ 진입 대상 프레임은 <b>들어갈 수 있을 때만</b> 싣는다</h3>
     * <p>비는 사유가 둘(프레임 없음 · 진입 불가)인데 값은 하나로만 말한다 — 인지·수용한 대가이며
     * 화면은 공통 문구를 쓴다. ⚠ 이 좁힘은 화면이 <b>미리</b> 막게 하려는 것이고 서버 진입 가드를
     * 대신하지 않는다.
     */
    private PortalUserWorkResponse toResponse(UserWorkRow row,
                                              Map<Long, LocalDateTime> firstAuthoredAt,
                                              Map<Long, PortalUploadAsset> assets,
                                              Map<Long, LocalDateTime> lastLabelSavedAt,
                                              Set<Long> enterableDatamart,
                                              PortalRetentionPolicy.DatamartExpiry datamartExpiry,
                                              PortalRetentionPolicy.UploadExpiry uploadExpiry) {
        LocalDateTime expiresAt = null;
        String videoName = row.videoName();
        Long entrySrcSn = row.entrySrcSn();

        if (row.isUpload()) {
            PortalUploadAsset asset = assets.get(row.rawSn());
            if (asset != null) {
                // 표시 이름은 사용자가 올린 원본 파일명이 1순위다. 보관값이 없으면 클립 식별자로 남긴다
                // (빈 이름을 지어내지 않는다 — 「모른다」와 「이름이 없다」는 다르다).
                if (asset.orgnlFileNm() != null && !asset.orgnlFileNm().isBlank()) {
                    videoName = asset.orgnlFileNm();
                }
                if (uploadExpiry != null) {
                    expiresAt = uploadExpiry.expiresAt(asset.uldSttsCd(), asset.regDt(),
                            asset.sttsChgDt(), lastLabelSavedAt.get(row.rawSn()));
                }
            }
        } else {
            if (!enterableDatamart.contains(row.rawSn())) {
                // 들어갈 수 없는 행 — 프레임이 있어도 진입 대상을 비운다. 행 자체는 목록에 남고
                // 만료 고지도 그대로다(빼면 삭제 대상 작업물이 화면에서 사라진다).
                entrySrcSn = null;
            }
            if (datamartExpiry != null) {
                expiresAt = datamartExpiry.expiresAt(firstAuthoredAt.get(row.rawSn()));
            }
        }

        return new PortalUserWorkResponse(
                row.rawSn(),
                row.assetSource(),
                videoName,
                row.labelCount(),
                row.lastSavedAt(),
                entrySrcSn,
                PortalUserWorkResponse.toExpiresOn(expiresAt));
    }

    private void requireOwner(String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }
}
