package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.video.service.FrameImageLookupService.FrameSpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * SCR-REVIEW-002 — 프레임 이미지 서빙 (rawSn + frameNo 기반).
 *
 * <p>보안 (HIGH):
 * <ul>
 *   <li><b>Path Traversal (CWE-22)</b>: {@link #resolveSafe} 가 baseDir 외부 경로를 차단.</li>
 *   <li><b>비식별 강제</b>: PRVC/PSDO 영상은 무조건 {@code deidFilePath} 사용.
 *       deidFilePath 가 비어있으면 NOT_FOUND. 원본 경로 폴백 금지.</li>
 *   <li><b>확장자 allowlist</b>: jpg/jpeg/png/webp 만 서빙.</li>
 *   <li><b>로그 마스킹</b>: 사용자 입력 평문 path/filename 노출 금지.</li>
 *   <li><b>검증 대상 = 사용 대상 (CWE-367/59)</b>: 두 분기 모두 <b>판정에 쓴 실경로
 *       ({@code toRealPath})</b> 를 그대로 열고, open 은 {@code NOFOLLOW_LINKS} 로 수행한다.</li>
 * </ul>
 *
 * <h3>트랜잭션 경계 (W3 — 커넥션 기아 방어)</h3>
 * <p>이 빈에는 {@code @Transactional} 을 <b>두지 않는다</b>. 고빈도 서빙 경로에서 NAS 파일 I/O 가
 * DB 커넥션을 쥔 채 수행되면 커넥션 기아 교착으로 번지기 때문이다(실사고 이력). DB 조회·인가·게이트는
 * 전부 별도 빈 {@link FrameImageLookupService}(= {@code @Transactional(readOnly)}) 안에서 끝나고,
 * 여기서는 <b>값 스냅샷</b>({@link FrameSpec})만 받아 경로 검증·파일 I/O·응답 조립을 한다.
 * 같은 빈의 private 메서드로 나누면 self-invocation 이라 프록시를 타지 않아 트랜잭션이 통째로
 * 유실되므로(이 프로젝트의 실제 사고 패턴), 반드시 <b>별도 빈 주입</b>이어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FrameImageService {

    /**
     * 조회 전담 빈 — 인가(CWE-639) → 비식별 신고 게이트(412) → 값 추출까지가 <b>트랜잭션 안</b>이고,
     * 반환 이후(경로 검증·파일 I/O)는 <b>트랜잭션 밖</b>이다. 주입 호출이라 프록시를 반드시 경유한다.
     */
    private final FrameImageLookupService lookupService;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 비식별 프레임 base — 비식별 프레임({@code DE_IDNTF_SRC_FILE_PATH_NM})은 이 base 하위
     * ({@code frames/deid/{rawSn}})에 저장되므로, 그 경로의 검증 base 도 여기여야 한다.
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    // LOW-1 — 인가 컨텍스트(actor) 없이 서빙하던 2-arg 오버로드는 제거했다. 호출자가 0건인데
    // actor=null 로 서빙해 나중에 무심코 배선되면 즉시 인증 우회가 되기 때문이다(fail-open 표면 제거).
    // 프레임 서빙 진입점은 serve(rawSn, frameNo, allowRaw, actor) / serveBySrcSn / serveDeidentified 뿐이다.

    /**
     * Phase 3 — V2 비식별 정책 갱신.
     *
     * <ul>
     *   <li>모든 영상은 기본 DEID 프레임을 서빙 (라벨러는 RAW 못 봄).</li>
     *   <li>REVIEWER 가 명시적으로 {@code raw=true} 요청 시에만 원본(filePath) 서빙.</li>
     *   <li>WORKER 의 {@code raw=true} 는 무시 (강제 DEID).</li>
     *   <li>PRVC/PSDO 영상에서 DEID 가 준비되지 않은 경우: NOT_FOUND (기존 회귀 유지).</li>
     * </ul>
     *
     * @param rawSn     영상 PK
     * @param frameNo   프레임 번호 (0-base)
     * @param allowRaw  REVIEWER 한정 — true 면 원본 경로 사용
     * @param actor     호출자 (null 이면 raw 옵션 무시)
     */
    public ResponseEntity<Resource> serve(Long rawSn, Integer frameNo, boolean allowRaw, TokenClaims actor)
            throws IOException {
        // 1) 조회·게이트는 트랜잭션 안(별도 빈)에서 끝낸다 — 커넥션을 쥔 채 NAS I/O 를 하지 않는다(W3).
        //    S7 (DEV_FIX-A/H1 — HIGH, CWE-359) 신고 구간 게이트도 그 안에서 평가된다. 신고 시점에는
        //    DE_IDNTF_SRC_FILE_PATH_NM 이 이미 채워져 있으므로 아래 needsDeidentify() 분기만으로는
        //    "얼굴이 안 지워진 그 비식별본"이 200 으로 나간다(라벨 좌표보다 상위 위험 = 실제 PII 이미지).
        //    인가는 호출 측(VideoController.verifyRawAccess)이 이미 수행했고, 이 게이트는 그 뒤의
        //    프리컨디션이라 인가를 대체하지 않는다. resolve('F'→'Y') 로 자동 해제된다.
        FrameSpec spec = lookupService.byRawSnAndFrameNo(rawSn, frameNo);

        // 2) 경로 판정·검증·응답 조립은 단일 원천에 위임 (아래 serveFrame) — 트랜잭션 밖.
        return serveFrame(spec, allowRaw, actor, "frame_" + rawSn + "_" + frameNo);
    }

    /**
     * <b>프레임 PK({@code SRC_SN}) 기준</b> 프레임 이미지 서빙 —
     * {@code GET /v1/frames/{srcSn}/image} 백엔드. 라벨링 캔버스가 매 프레임 호출하는 경로다.
     *
     * <h3>왜 신설했는가 (실측 결함)</h3>
     * 이 경로는 컨트롤러가 {@code SRC_FILE_PATH_NM} 만 읽어 서빙했다. 그 결과
     * <ul>
     *   <li>해상도·증강 <b>파생 프레임</b>은 원본 픽셀이 실재하지 않아
     *       {@code SRC_FILE_PATH_NM} 이 null 이고(E-ISSUE-41 정책 A) <b>무조건 404</b> → 캔버스 백지.</li>
     *   <li>일반 영상에서는 <b>원본(비식별 전) 프레임</b>이 WORKER 에게 그대로 나갔다 — "라벨링은 비식별
     *       영상의 프레임으로 한다"는 설계가 이 경로에만 미배선된 상태였다.</li>
     * </ul>
     * 판정을 여기서 다시 구현하면 두 경로가 갈라지므로(이 프로젝트의 반복 사고 원인),
     * {@link #serve(Long, Integer, boolean, TokenClaims)} 와 <b>같은</b> {@link #serveFrame} 를 쓴다.
     *
     * <h3>순서 고정 (보안)</h3>
     * ①인가({@code LabelAccessGuard.verifyAndGet} — CWE-639 IDOR) → ②신고 구간 게이트(412, CWE-359)
     * → ③경로 해석. 게이트를 인가보다 앞에 두면 미배정 WORKER 가 응답 코드로 프레임 존재 여부를
     * 탐색할 수 있으므로 인가가 항상 먼저다. ①②는 {@link FrameImageLookupService#bySrcSn} 안(트랜잭션)
     * 에서, ③ 이후 파일 I/O 는 트랜잭션 밖에서 수행한다(W3).
     *
     * @param srcSn    프레임 PK
     * @param allowRaw REVIEWER 한정 — true 면 원본 경로 사용(WORKER 요청은 무시하고 DEID 강제)
     * @param actor    호출자 토큰 클레임
     */
    public ResponseEntity<Resource> serveBySrcSn(Long srcSn, boolean allowRaw, TokenClaims actor)
            throws IOException {
        // 1) 인가(IDOR) → 신고 구간 게이트(412) → 비식별 정책 판정값 조회까지 <b>트랜잭션 안</b>에서 끝난다
        //    (별도 빈 = 프록시 경유). 이후 파일 I/O 는 커넥션을 쥐지 않는다(W3).
        FrameSpec spec = lookupService.bySrcSn(srcSn, actor);

        return serveFrame(spec, allowRaw, actor, "frame_" + srcSn);
    }

    /**
     * 프레임 이미지 <b>경로 판정 · 경로 검증 · 응답 조립 단일 원천</b>.
     *
     * <p>정책(V2):
     * <ul>
     *   <li>모든 영상은 기본 DEID 프레임을 서빙 (라벨러는 RAW 못 봄).</li>
     *   <li>REVIEWER 가 명시적으로 {@code allowRaw=true} 요청 시에만 원본(filePath) 서빙.</li>
     *   <li>WORKER 의 {@code allowRaw=true} 는 무시 (강제 DEID).</li>
     *   <li>DEID 경로가 없을 때: PRVC/PSDO 는 NOT_FOUND, ANONY 는 원본 폴백.</li>
     *   <li><b>외부 산출물 이관</b>({@code SRC_TYPE='IMPORTED'})은 위 NOT_FOUND 의 예외 —
     *       가져온 프레임을 역할 구분 없이 그대로 서빙한다(아래 분기 주석 참조).</li>
     * </ul>
     *
     * <p><b>트랜잭션 밖</b>에서 실행된다(W3) — 인자는 조회 전담 빈이 트랜잭션 안에서 뽑아 준 값
     * 스냅샷이라 여기서 엔티티/지연 로딩을 건드리지 않는다.
     *
     * @param fileNameBase Content-Disposition 파일명 접두 — 호출 경로별 식별자(서버가 통제하는 값만)
     */
    private ResponseEntity<Resource> serveFrame(FrameSpec spec, boolean allowRaw,
                                                TokenClaims actor, String fileNameBase) throws IOException {
        Long rawSn = spec.rawSn();
        Long frameNo = spec.frameNo();

        // 3) Phase 3 — V2 정책: 기본 DEID, REVIEWER 가 명시적으로 raw=true 요청 시에만 원본 허용
        boolean reviewerRequestedRaw = allowRaw && actor != null && actor.role() == Role.REVIEWER;
        String relPath;
        // 선택된 경로가 <b>비식별 컬럼</b>에서 왔는지 추적한다 — 경로 검증 base 를 그 출처에 맞춰 고른다.
        boolean fromDeidColumn = false;
        if (reviewerRequestedRaw) {
            relPath = spec.srcFilePath();
        } else {
            String deid = spec.deidFilePath();
            if (deid != null && !deid.isBlank()) {
                relPath = deid;
                fromDeidColumn = true;
            } else if (spec.imported()) {
                // 외부 산출물 이관(SRC_TYPE='IMPORTED') — 가져온 프레임을 그대로 보여 준다.
                //
                // 왜 예외인가: 이 경로의 프레임은 <b>외부에서 이미 라벨링이 끝난 산출물</b>이고, 우리가
                // 비식별 단계를 태워 만든 짝이 아직 없을 수 있다. 그런데 산출물이 개인정보 포함으로
                // 표기돼 오면 아래 needsDeidentify() 분기에 걸려 프레임이 전부 404 가 되고, 그러면
                // <b>검수 화면이 백지</b>가 되어 검수 자체가 성립하지 않는다. 이 경로는 적재 직후
                // 곧바로 검수 대기이므로 프레임을 못 보는 것은 기능의 부재와 같다.
                //
                // 판정축을 PRVC_TYPE_CD 가 아니라 <b>출처유형</b>으로 둔 것도 같은 이유다 — 그 컬럼을
                // ANONY 로 눕혀 우회하면 비식별 대상 판정(needsDeidentify)까지 함께 바뀌어 의도보다
                // 넓게 영향이 간다. 여기서 바뀌는 것은 <b>이 경로의 서빙 하나</b>뿐이다.
                //
                // 역할로 가르지 않는다(확정 정책) — 작업자와 검수자 모두에게 같은 프레임을 보여 준다.
                // 승인을 붙잡아 두는 일은 LS_RAW_DATA_STATUS.DE_IDNTF_CMPTN_YN 이 따로 한다.
                // 비식별 누락 신고 게이트는 이 분기보다 <b>앞</b>(조회 전담 빈)에서 이미 평가되므로
                // 그 구간에는 여기까지 오지 않는다.
                relPath = spec.srcFilePath();
            } else if (spec.needsDeidentify()) {
                // PRVC/PSDO — DEID 미준비 시 원본 노출 금지 (기존 회귀)
                log.warn("[FrameImage] deid path missing for sensitive video rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
            } else {
                // ANONY + DEID 미준비 → 원본 폴백 (V2 정책상 비식별 우선이지만 ANONY 는 정책상 원본 노출 무방)
                relPath = spec.srcFilePath();
            }
        }

        // 4) Path Traversal 방어 — 경로의 <b>출처 컬럼</b>에 맞는 base 로 검증한다(CWE-22 + CWE-359).
        //    비식별 프레임은 deidentified-path 하위에 저장되므로 rawBase 로만 검증하면 정상 비식별본이
        //    전부 FORBIDDEN 이 된다(실측: rawSn=26 프레임 403). 반대로 원본 경로에 deidBase 를 허용하면
        //    격리가 깨지므로, 출처별로 단일 base 를 고르고 비식별은 서브트리까지 강제한다.
        Path resolved;
        if (fromDeidColumn) {
            Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
            // H-2 — 서빙도 export 와 <b>같은 단일 판정기</b>를 쓴다: base 포함 + 존재/정규파일 +
            // <b>실경로(toRealPath) 기준</b> 비식별 서브트리. lexical 검사만 하면 base 내부 심링크
            // (frames/deid/x.jpg → frames/raw/x.jpg)로 원본 프레임이 "비식별본"으로 서빙된다(CWE-59/359).
            StorageSubtreePolicy.Verification v =
                    StorageSubtreePolicy.verifyDeidentifiedFile(deidBase, relPath);
            if (!v.ok()) {
                log.warn("[FrameImage] deid frame rejected rawSn={} frameNo={} verdict={}", rawSn, frameNo, v.verdict());
                throw switch (v.verdict()) {
                    case MISSING, NOT_REGULAR_FILE, REALPATH_FAILED, BLANK ->
                            new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
                    default -> new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
                };
            }
            resolved = v.path();
        } else {
            Path rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
            Path lexical = resolveSafe(rawBase, relPath);
            // 5) 파일 존재 확인
            if (!Files.exists(lexical) || !Files.isRegularFile(lexical)) {
                log.warn("[FrameImage] file not found rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
            }
            // H-2 — 원본 경로도 실경로 재검증(심링크로 base 밖 파일을 서빙하는 우회 차단).
            // M-2 (CWE-367/22) — 판정한 <b>실경로를 그대로 사용</b>한다. lexical 경로를 열면
            // "실경로로 검증하고 lexical 경로로 연다"가 되어 검증 대상과 사용 대상이 달라지고,
            // 검증~open 사이에 심링크를 바꿔치기하면 base 밖 파일이 서빙된다
            // (StorageSubtreePolicy.Verification 의 A-1 규약과 동일 — 비식별 분기는 이미 그렇게 한다).
            try {
                Path real = lexical.toRealPath();
                if (!real.startsWith(rawBase.toRealPath())) {
                    log.warn("[FrameImage] symlink escaping base rawSn={} frameNo={}", rawSn, frameNo);
                    throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
                }
                resolved = real;
            } catch (IOException e) {
                log.warn("[FrameImage] realpath resolution failed rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
            }
        }

        // 6) MIME 결정 (allowlist 기반) — 실경로 기준(위에서 판정한 그 파일).
        MediaType mediaType = resolveMediaType(resolved);

        // 7) Stream 응답 (대용량 메모리 적재 회피).
        //    M-1 — open 은 형제 경로(serveDeidentified)와 <b>동일 규약</b>인 NOFOLLOW 로 한다.
        //    두 분기 모두 위에서 실경로로 판정을 끝냈으므로 정상 배치에서는 링크가 아니고,
        //    판정~open 사이에 최종 컴포넌트가 심링크로 교체되면 열지 않고 실패시킨다(fail-closed).
        OpenedFile opened;
        try {
            opened = openNoFollow(resolved);
        } catch (IOException e) {
            // 내부 경로/원인 노출 없이 규약 4xx 로 끝낸다(CWE-209, OWASP A10) — 식별자 + 예외 클래스명만 로그.
            log.warn("[FrameImage] open failed rawSn={} frameNo={} reason={}",
                    rawSn, frameNo, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }
        InputStreamResource body = new InputStreamResource(opened.stream());

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(opened.size())
                // 신고 게이트(위 1-1)가 매 요청 평가되려면 클라이언트 캐시가 응답을 재사용하면 안 된다 —
                // max-age 동안 캐시된 마스킹 실패 프레임이 그대로 재노출된다(CWE-359/525).
                // 비식별 프레임 경로(serveDeidentified)·영상 스트림과 동일하게 no-store 로 통일.
                .cacheControl(CacheControl.noStore())
                // 헤더에는 서버가 통제하는 값만 넣는다 — 파일시스템 유래 파일명을 실으면 CRLF 주입
                // (CWE-113) 표면이 생기므로 호출 경로 식별자 + allowlist MIME 파생 확장자로만 조립한다
                // (/deid-image 와 동일 규약).
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + fileNameBase + extOf(mediaType) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * Phase 1 — <b>비식별 프레임 이미지</b>를 프레임 PK({@code SRC_SN}) 로 서빙한다.
     * ({@code GET /v1/frames/{srcSn}/deid-image} 백엔드)
     *
     * <h3>왜 {@link #serve(Long, Integer, boolean, TokenClaims)} 를 재사용하지 않는가</h3>
     * 위 메서드는 (rawSn, frameNo) 키이며 ANONY 영상에서 비식별본이 없으면 <b>원본으로 폴백</b>한다.
     * 해상도 파생 프레임은 원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 null 이고
     * (E-ISSUE-41 정책 A), 이 엔드포인트의 계약은 "비식별 벌만 서빙"이다. 그래서 폴백이 없는
     * 별도 경로를 둔다 — 이 메서드는 {@code getSrcFilePathNm()} 을 <b>참조하지 않는다</b>.
     *
     * <h3>순서 고정 (보안)</h3>
     * ①인가({@code LabelAccessGuard} — CWE-639 IDOR) → ②신고 구간 게이트(CWE-359, 412) →
     * ③경로 해석. 게이트를 인가보다 앞에 두면 미배정 WORKER 가 412/404 로 프레임 존재 여부를
     * 탐색할 수 있으므로 인가가 항상 먼저다.
     *
     * <p>경로 검증은 {@link StorageSubtreePolicy#verifyDeidentifiedFile} <b>단일 판정기</b>에 위임한다
     * (raw·deid 두 base 동일 운영 형상 대응 + {@code toRealPath} 심링크 우회 차단, CWE-22/59).
     *
     * @param srcSn 프레임 PK
     * @param actor 호출자 토큰 클레임
     */
    public ResponseEntity<Resource> serveDeidentified(Long srcSn, TokenClaims actor) throws IOException {
        // 1) 인가(IDOR) → 2) 신고 구간 게이트(412, resolve 로 자동 해제) → 비식별 경로 추출까지가
        //    <b>트랜잭션 안</b>(별도 빈 = 프록시 경유)이고, 이후 파일 I/O 는 커넥션을 쥐지 않는다(W3).
        //    게이트 판정은 공유 게이트(DeidentReportGate)가 <b>이 영상 행</b> 기준으로 수행한다 —
        //    여기서 'F' 비교를 국소 재구현하지 않는다(게이트 이원화 금지).
        String deidPath = lookupService.deidPathBySrcSn(srcSn, actor);

        // 3) 비식별 경로만 사용 — 원본 폴백 금지. 비면 404(원본 유출 차단).
        Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(deidBase, deidPath);
        if (!v.ok()) {
            log.warn("[FrameDeidImage] rejected srcSn={} verdict={}", srcSn, v.verdict());
            throw switch (v.verdict()) {
                case BLANK, MISSING, NOT_REGULAR_FILE, REALPATH_FAILED ->
                        new CustomException(ErrorCode.NOT_FOUND, "비식별 이미지 파일이 존재하지 않습니다.");
                default -> new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
            };
        }
        Path resolved = v.path();

        // 4) 확장자 allowlist 기반 MIME (CWE-434)
        MediaType mediaType = resolveMediaType(resolved);

        // 5) 스트림 응답 — 대용량 메모리 적재 회피.
        //    NOFOLLOW_LINKS 필수(TOCTOU, CWE-367/59): 판정({@code verifyDeidentifiedFile})은 실경로
        //    기준이지만, 판정~open 사이에 그 <b>최종 컴포넌트</b>를 원본 프레임을 가리키는 심링크로
        //    교체하면 링크를 따라가 원본 픽셀이 "비식별본"으로 서빙된다. frames/deid/** 에 심링크는
        //    정상 산출물이 아니므로 링크면 열지 않고 실패시킨다(fail-closed). 크기도 같은 옵션으로 읽어
        //    판정 대상과 응답 대상이 어긋나지 않게 한다.
        OpenedFile opened;
        try {
            opened = openNoFollow(resolved);
        } catch (IOException e) {
            // 권한·교체·삭제 등 — 내부 경로/원인 노출 없이 규약 4xx 로 끝낸다(CWE-209, OWASP A10).
            log.warn("[FrameDeidImage] open failed srcSn={} reason={}", srcSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 이미지 파일이 존재하지 않습니다.");
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(opened.size())
                // 신고(비식별 누락) 즉시 차단이 클라이언트에서도 성립해야 한다 — 캐시된 마스킹 실패
                // 이미지를 max-age 동안 재노출하면 방금 세운 412 게이트가 무력화된다(CWE-359).
                .cacheControl(CacheControl.noStore())
                // 헤더에는 서버가 통제하는 값만 넣는다 — 파일명 유래 문자열을 넣으면 CRLF 주입
                // (CWE-113) 표면이 생기므로 srcSn + MIME 파생 확장자로만 조립한다.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"frame_deid_" + srcSn + extOf(mediaType) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(opened.stream()));
    }

    /**
     * 열린 파일 — 크기와 스트림을 <b>같은 open 규약</b>으로 얻은 한 쌍.
     * (크기는 A 규약, 스트림은 B 규약으로 읽으면 판정 대상과 응답 대상이 어긋난다.)
     */
    public record OpenedFile(long size, InputStream stream) {
    }

    /**
     * <b>심링크를 따라가지 않는 open</b> — 두 서빙 분기(원본/비식별)의 공통 규약.
     *
     * <p><b>왜 NOFOLLOW 인가 (TOCTOU, CWE-367/59)</b>: 경로 판정은 실경로({@code toRealPath}) 기준으로
     * 끝나지만, 판정~open 사이(수 ms)에 그 <b>최종 컴포넌트</b>를 원본 프레임을 가리키는 심링크로
     * 교체하면 링크를 따라가 마스킹 전 원본 픽셀이 200 으로 나간다. 서빙 대상 경로에 심링크는 정상
     * 산출물이 아니므로 링크면 열지 않고 실패시킨다(fail-closed). 크기도 같은 옵션으로 읽어 판정 대상과
     * 응답 대상이 어긋나지 않게 한다.
     *
     * <p>실패는 호출측이 내부 원인 노출 없이 404 로 마감한다(CWE-209).
     *
     * <p><b>구현은 한 벌만 둔다(public static)</b>: 같은 규약이 필요한 다른 서빙 경로
     * — 특히 <b>외부 채널</b>인 포털의 데이터마트 프레임 서빙
     * ({@code PortalLabelService#serveFrameImage}) — 이 이 헬퍼를 <b>재사용</b>한다.
     * 규약을 경로마다 재구현하면 "판정기/open 규약 단일화"라는 이 방어의 전제가 무너지고,
     * 한쪽만 {@code Files.newInputStream}(링크 추종)으로 되돌아가도 아무도 알아채지 못한다.
     *
     * @param file <b>검증을 마친 실경로</b>(lexical 경로를 넘기면 안 된다 — M-2 참조)
     */
    public static OpenedFile openNoFollow(Path file) throws IOException {
        long size = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        return new OpenedFile(size, in);
    }

    /** allowlist MIME → 확장자. 사용자/파일시스템 유래 문자열을 헤더에 싣지 않기 위한 역매핑. */
    private static String extOf(MediaType mediaType) {
        if (MediaType.IMAGE_PNG.equals(mediaType)) {
            return ".png";
        }
        if ("webp".equals(mediaType.getSubtype())) {
            return ".webp";
        }
        return ".jpg";
    }

    /**
     * Path Traversal 방어 (CWE-22) — baseDir 외부 경로는 FORBIDDEN.
     * <p>VisibleForTesting (public static).
     */
    public static Path resolveSafe(Path baseDir, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
        }
        return resolved;
    }

    /** 확장자 allowlist 기반 MIME 결정 — 그 외는 거부. VisibleForTesting. */
    public static MediaType resolveMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 확장자입니다.");
    }
}
