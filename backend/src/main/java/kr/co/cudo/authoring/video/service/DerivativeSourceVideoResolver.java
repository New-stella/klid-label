package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 증강 파생영상이 <b>복사할 영상 파일 경로</b>를 구하는 <b>단일 진실원</b>.
 * 하는 일은 하나다 — <b>경로를 조달한다. 못 구하면 실패한다.</b>
 *
 * <h3>★ 비식별은 애초에 전제조건이 아니었다 (2026-09-02 사용자 확정, 구속)</h3>
 * <p>흡수 이전 이 자리에는 비식별 플래그 판정({@code hasDeidentArtifact()})이 있었고 그래서
 * 「비식별이 완료된 것만 증강한다」로 읽혔다. <b>그것은 조건이 아니라 결과였다.</b>
 *
 * <table>
 *   <caption>진짜 전제조건</caption>
 *   <tr><th>채널</th><th>전제조건</th><th>비식별은</th></tr>
 *   <tr><td>관제</td><td><b>검수 완료</b></td><td>그 안에 이미 포함된 <b>결과</b>일 뿐</td></tr>
 *   <tr><td>포털</td><td><b>본인 자산</b>(검수가 없다)</td><td>해당 없음</td></tr>
 * </table>
 *
 * <p>결과로 따라오는 사실을 <b>별도 조건으로 코드에 적은 것</b>이 문제였다. 조건이 하나 늘었고,
 * 그 사실이 성립하지 않는 채널이 생기자 막혔다. 그래서 그 판정을 <b>걷어냈다</b>.
 *
 * <h3>★★ 되살리지 말 것 — 되살리면 포털만 막힌다</h3>
 * <p>「방어적으로 한 번 더 본다」는 이유로 비식별 판정을 다시 넣지 마라.
 * <b>관제 경로에서 그 판정은 한 번도 걸릴 수 없다</b>:
 * <ol>
 *   <li>증강 요청은 <b>검수 완료 영상만</b> 받는다({@code AugmentRequestService} — 미검수는 거부).</li>
 *   <li>검수까지 갔으면 비식별은 끝나 있다 — 비식별이 파이프라인의 <b>선두</b>다.</li>
 *   <li>비식별 상태가 되돌아가는 경로가 <b>0건</b>이다(실측: {@code markDeidentified("N")} 호출부 0건.
 *       {@code 'F'} 로 가는 신고 경로는 있으나 그것은 <b>산출물이 존재</b>하는 상태다).</li>
 * </ol>
 * ⇒ 관제에서 아무 일도 하지 않는 판정이고, 포털에서만 <b>전건을 막는다</b>.
 *
 * <h3>관제 동작은 그대로다 — 오히려 더 엄격해진다</h3>
 * <p>플래그가 {@code 'Y'} 여도 <b>파일이 없을 수 있다</b>(이 저장소가 그 사실을 알고 뒤에 실재 검증을
 * 덧대 뒀다 — {@link ParentDeidArtifactGuard}). 경로를 직접 보면 그 뒷받침이 <b>앞으로 당겨진다.</b>
 * 비식별 산출물이 없으면 여전히 실패하고, <b>원본 폴백은 어디에도 없다</b>.
 *
 * <h3>포털 자산이 원본을 복사하는 근거</h3>
 * <p>관제 자산이 원본을 복사하지 못하게 막은 이유는 <b>남의 개인정보 유출</b>이다 — 「비식별 완료」로
 * 표시된 파생 행에 원본 경로가 실리면 그 값이 승인 동결을 거쳐 관제 조회 통로까지 나간다.
 * 포털 자산은 <b>본인이 올려 본인만 보는 데이터</b>라 그 근거가 성립하지 않는다.
 * ⚠ 인지·수용한 대가: 증강은 <b>외부 생성 AI 로 나가는 위탁</b>이라, 이 확정은 포털 사용자의 원본
 * 영상이 외부 업체로 나간다는 뜻이다. 알고 선택했다.
 *
 * <h3>★ 예외 분기를 만들지 않는다 — fail-closed 가 구조로 보장된다</h3>
 * <p>「포털이면 건너뛴다」로 만들지 않았다. 그러면 출처를 판별하지 못했을 때 어디로 떨어질지가
 * 애매하고, 예외가 하나 더 생기면 또 붙는다. 대신 <b>묻는 질문을 하나로 통일</b>하고
 * <b>조달하는 방법만</b> 출처로 가른다({@link Source}). 분기의 <b>기본값이 비식별본</b>이라
 * 출처 판별을 놓쳐도 관제 원본은 나가지 않는다 — 판별에 실패하면 비식별본을 찾다가 저절로 실패한다.
 *
 * <h3>해상도 파생은 이 판정기를 쓰지 않는다 (의도)</h3>
 * <p>해상도 파생은 포털 채널에 제공하지 않으므로 열 이유가 없다 — 넓히지 않는 것이 안전하다.
 * 그 경로들({@code ResolutionReservationPersister} · {@code ResolutionSnapshotService} ·
 * {@code ResolutionPersistService} · {@link ParentDeidArtifactGuard})은 종전대로
 * {@code hasDeidentArtifact()} 를 직접 보며, 이 클래스가 그 헬퍼를 <b>바꾸지 않으므로 동작이 그대로다</b>.
 * 포털 자산에 해상도 파생을 요청하면 그 게이트에서 거부되는데 그것이 의도한 결과다.
 *
 * @design ADR-058
 * @design ERD-028
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DerivativeSourceVideoResolver {

    /**
     * 복사 재료의 <b>조달처</b>. 출처 판별은 {@link #sourceOf(LsDataRaw)} 한 곳에서만 하고
     * 호출부는 분기하지 않는다.
     */
    public enum Source {
        /** 부모의 비식별 영상 산출물 — 관제 인입·이관·파생 등 <b>기본값</b>. */
        DEIDENTIFIED,
        /** 포털 사용자가 올린 <b>본인 원본</b> — 포털 업로드 자산에만 해당한다. */
        PORTAL_ORIGINAL
    }

    /**
     * 부모 비식별 <b>영상</b> 경로의 진실원. 파일명은 고정이 아니므로(mock={@code deidentified.mp4} ·
     * KPST={@code {원본stem}-mask{ext}}) <b>조합·추측하지 않고</b> 적재된 값을 읽는다.
     */
    private final LsDeidentProcLogRepository deidentProcLogRepository;

    /** co-locate 비식별 영상 디렉터리({@code dirname(원본)/{rawSn}/deid/}) 판정용. */
    private final VideoArtifactRootResolver artifactRootResolver;

    /** 포털 업로드 저장 루트 — 포털 원본 경로의 허용 범위 판정용(설정 기본값을 복제하지 않는다). */
    private final PortalUploadProperties portalUploadProperties;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 이 영상의 복사 재료가 <b>어디서 오는가</b> — 출처 판별 단일 지점.
     *
     * <p>★ {@link Source#DEIDENTIFIED} 가 <b>기본값</b>이다. 새 출처 값이 생겨도, 값이 비어 있어도,
     * 판별을 놓쳐도 여기로 떨어진다 — 그래야 관제 원본이 새지 않는다.
     */
    public Source sourceOf(LsDataRaw parent) {
        // 판정은 엔티티의 단일 헬퍼에 위임한다 — 판별자 상수를 여기서 다시 비교하면 채널 판정이
        // 두 곳이 된다. null 검사는 여기 남는다: 부모가 없으면 판별 자체가 불가능하므로 기본값으로
        // 떨어져야 하고, 그 기본값이 DEIDENTIFIED 라 관제 원본이 새지 않는다(fail-closed).
        return parent != null && parent.isPortalUpload()
                ? Source.PORTAL_ORIGINAL
                : Source.DEIDENTIFIED;
    }

    /**
     * <b>복사할 영상 파일 경로를 구한다 — 못 구하면 비어 있다.</b> 예외를 던지지 않으므로 부모 게이트가
     * 이 값으로 「보류가 아니라 실패로 확정」을 고를 수 있다.
     *
     * <p>파일 시스템을 건드리지 않는다 — 적재된 값을 읽고 허용 범위를 따질 뿐이다. 실제 파일 실재는
     * 복사를 수행하는 단계가 확인한다(순서는 흡수 이전과 같다).
     */
    public Optional<Path> resolveQuietly(LsDataRaw parent) {
        try {
            return Optional.of(requireSourceVideo(parent));
        } catch (CustomException e) {
            // 경로 원문·내부 구조는 담지 않는다(CWE-209/532) — 식별자와 조달처만.
            log.warn("[DerivativeSource] no copyable source video rawSn={} source={}",
                    parent == null ? null : parent.getRawSn(),
                    parent == null ? null : sourceOf(parent));
            return Optional.empty();
        }
    }

    /**
     * <b>복사할 영상 파일 경로를 구한다. 못 구하면 실패한다.</b>
     *
     * <p>경로는 어느 쪽이든 <b>적재된 값</b>을 읽고 문자열로 조합하지 않는다 — 비식별본은 처리 이력
     * ({@code DE_IDNTF_FILE_PATH_NM}), 포털 원본은 영상 행({@code RAW_FILE_PATH_NM})이다.
     * 어느 경우에도 <b>다른 쪽으로 폴백하지 않는다</b>.
     *
     * @throws CustomException {@link ErrorCode#NOT_FOUND} 적재된 경로가 없다 /
     *                         {@link ErrorCode#INVALID_INPUT} 허용 저장 경로 밖(CWE-22, 경로 원문 미노출)
     */
    public Path requireSourceVideo(LsDataRaw parent) {
        if (parent == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }
        return switch (sourceOf(parent)) {
            case PORTAL_ORIGINAL -> requirePortalOriginal(parent);
            case DEIDENTIFIED -> requireDeidentifiedVideo(parent);
        };
    }

    // ------------------------------------------------------------------
    // DEIDENTIFIED — 흡수 이전 AugmentExtractSnapshot#resolveParentDeidVideo 와 동일 동작
    // ------------------------------------------------------------------

    /**
     * 부모 비식별 영상 경로. 최신 SUCCESS 처리 이력의 값을 읽고, 없으면 <b>원본으로 폴백하지 않고</b>
     * 실패시킨다(개인정보 복제 차단, CWE-359).
     *
     * <p>경로 검증은 <b>2-way</b>다 — ①co-locate 비식별 영상 디렉터리 하위 또는 ②비식별 저장소의
     * 비식별 전용 서브트리. 둘 다 아니면 거부한다(fail-secure).
     */
    private Path requireDeidentifiedVideo(LsDataRaw parent) {
        Long parentRawSn = parent.getRawSn();
        String deidVideoPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 비식별 영상 경로를 찾을 수 없습니다: parentRawSn=" + parentRawSn));

        Optional<Path> coLocateDir = (artifactRootResolver == null)
                ? Optional.empty()
                : artifactRootResolver.deidVideoDirQuietly(parentRawSn, parent.getRawFilePathNm());
        if (coLocateDir.isPresent()) {
            Path candidate = Paths.get(deidVideoPath);
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : coLocateDir.get().resolve(candidate).normalize();
            if (resolved.startsWith(coLocateDir.get())) {
                return resolved;
            }
        }
        Path base = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path candidate = Paths.get(deidVideoPath);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!StorageSubtreePolicy.isDeidentifiedArtifact(base, resolved)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 비식별 저장 경로를 벗어납니다.");
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // PORTAL_ORIGINAL — 본인이 올린 원본
    // ------------------------------------------------------------------

    /**
     * 포털 업로드 자산의 <b>자기 원본 파일</b> 경로. 값은 영상 행에 적재된 것을 그대로 읽고,
     * <b>포털 업로드 저장 루트 안</b>인지 확인한다(CWE-22). 밖이면 거부하며 비식별본으로 폴백하지 않는다.
     *
     * <p>이 값이 원본인 것이 문제가 되지 않는 이유는 클래스 주석의 「포털 자산이 원본을 복사하는 근거」다.
     */
    private Path requirePortalOriginal(LsDataRaw parent) {
        String stored = parent.getRawFilePathNm();
        if (stored == null || stored.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND,
                    "업로드 영상 파일 경로를 찾을 수 없습니다: rawSn=" + parent.getRawSn());
        }
        Path base = Paths.get(portalUploadProperties.storagePath()).toAbsolutePath().normalize();
        Path candidate = Paths.get(stored);
        Path resolved = candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        if (!resolved.startsWith(base)) {
            // 경로 원문은 로그·응답 어디에도 담지 않는다(CWE-209/532).
            log.warn("[DerivativeSource] portal original outside upload root — reject rawSn={}", parent.getRawSn());
            throw new CustomException(ErrorCode.INVALID_INPUT, "경로가 허용된 업로드 저장 경로를 벗어납니다.");
        }
        return resolved;
    }
}
