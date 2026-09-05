package kr.co.cudo.authoring.notice.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.notice.entity.LsNotice;
import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;
import kr.co.cudo.authoring.notice.repository.LsNoticeAttachRepository;
import kr.co.cudo.authoring.notice.repository.LsNoticeQueryRepository;
import kr.co.cudo.authoring.notice.repository.LsNoticeQueryRepository.SearchField;
import kr.co.cudo.authoring.notice.repository.LsNoticeRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NoticeService} — <b>역할 계층(관리자 &gt; 검수자) 반영</b> 회귀 가드.
 * [design: ADR-055] [design: ROLE-004] [design: AC-125]
 *
 * <h3>고정하는 자리 — 이 도메인은 판정 지점이 한 곳이다</h3>
 * <p>{@code NoticeService.isReviewerOrAbove(actor)} 하나가 게시판 관리 권한을 판정하고,
 * 첨부 3경로({@link NoticeAttachService} 의 upload/download/delete)가 전부
 * {@link NoticeService#get(long, TokenClaims)} 에 위임하므로 그 한 곳이 그대로 파급된다.
 * 동등 비교로 되돌리면 관리자는 <b>목록에서 DRAFT 가 사라지고</b>, DRAFT 상세와
 * <b>그 공지의 첨부 업로드·다운로드·삭제가 전부 404</b> 가 된다.
 *
 * <h3>시험이 헛돌지 않게 하는 장치</h3>
 * <ul>
 *   <li>첨부 시험은 <b>공지를 DRAFT 로 둔다</b> — PUBLISHED 면 작업자도 통과해 계층이 증명되지 않는다.</li>
 *   <li>거부 축은 상태코드만 보지 않고 <b>부수효과 부재</b>까지 단언한다
 *       (첨부 리포지토리 무접촉 · 저장 파일 미생성) — 거부 사유가 둘인 경로에서
 *       뒤따르는 검사가 같은 거부를 던져 시험이 조용히 항상-참이 되는 것을 막는다.</li>
 *   <li>목록은 "예외가 안 났다"가 아니라 <b>{@code publishedOnly} 인자값</b>을 포획해 단언한다.</li>
 * </ul>
 *
 * <h3>적대검증(mutation) 실증</h3>
 * <p>판정을 {@code actor != null && actor.role() == Role.REVIEWER} 로 되돌리면
 * 이 클래스의 관리자 시험 <b>5건</b>이 FAILED 가 된다(아래 {@code admin*} 메서드).
 * 대조군(작업자·포털 회원·역할 미배정·검수자)은 그대로 통과한다.
 */
class NoticeServiceRoleHierarchyTest {

    private static final long DRAFT_NOTICE_SN = 9301L;
    private static final long ATTACH_SN = 9401L;

    private LsNoticeRepository noticeRepository;
    private LsNoticeQueryRepository noticeQueryRepository;
    private LsNoticeAttachRepository attachRepository;
    private NoticeService service;
    private NoticeAttachService attachService;

    @TempDir
    Path storageRoot;

    /** 다운로드 대상 실제 파일 — 가시성 통과 <b>이후</b> 검사까지 전부 성공시키기 위한 픽스처. */
    private Path storedFile;

    @BeforeEach
    void setUp() throws Exception {
        noticeRepository = mock(LsNoticeRepository.class);
        noticeQueryRepository = mock(LsNoticeQueryRepository.class);
        attachRepository = mock(LsNoticeAttachRepository.class);
        UserNameResolver userNameResolver = mock(UserNameResolver.class);

        service = new NoticeService(noticeRepository, noticeQueryRepository,
                mock(NoticeAttachService.class), userNameResolver);
        // 첨부 서비스는 <실제> NoticeService 를 물린다 — 위임 seam 자체가 시험 대상이다.
        attachService = new NoticeAttachService(attachRepository, service, storageRoot.toString());

        // ★ 공지는 DRAFT 다. PUBLISHED 면 작업자도 통과해 계층이 증명되지 않는다.
        LsNotice draft = LsNotice.create("초안 공지", "본문", false, "1");
        ReflectionTestUtils.setField(draft, "noticeSn", DRAFT_NOTICE_SN);
        when(noticeRepository.findById(DRAFT_NOTICE_SN)).thenReturn(Optional.of(draft));

        when(noticeQueryRepository.search(any(), any(), anyBoolean(), any()))
                .thenReturn(Page.empty());

        Path attachDir = storageRoot.resolve("notice-attach");
        Files.createDirectories(attachDir);
        storedFile = attachDir.resolve("11111111-2222-3333-4444-555555555555.pdf");
        Files.write(storedFile, "attach-body".getBytes(StandardCharsets.UTF_8));

        LsNoticeAttach attach = LsNoticeAttach.create(
                DRAFT_NOTICE_SN, "첨부.pdf", storedFile.getFileName().toString(),
                storedFile.toString(), Files.size(storedFile));
        ReflectionTestUtils.setField(attach, "attachSn", ATTACH_SN);
        when(attachRepository.findByAttachSnAndNoticeSn(ATTACH_SN, DRAFT_NOTICE_SN))
                .thenReturn(Optional.of(attach));
        when(attachRepository.save(any(LsNoticeAttach.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static TokenClaims internal(String sub, Role role) {
        return new TokenClaims(sub, role, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private static TokenClaims admin() {
        return internal("969300041", Role.ADMIN);
    }

    private static ErrorCode errorCodeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    private boolean capturePublishedOnly(TokenClaims actor) {
        Pageable pageable = PageRequest.of(0, 20);
        service.search(SearchField.ALL, null, pageable, actor);
        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(noticeQueryRepository).search(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static MockMultipartFile upload() {
        return new MockMultipartFile("file", "신규첨부.pdf", "application/pdf",
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 계층 본체 (관리자 = 검수자 이상) ----------

    @Test
    @DisplayName("★관리자_목록조회는_DRAFT를_포함한다_publishedOnly가_거짓")
    void adminListIncludesDraft() {
        assertThat(capturePublishedOnly(admin())).isFalse();
    }

    @Test
    @DisplayName("★관리자는_DRAFT_공지_상세를_조회한다_404가_아니다")
    void adminReadsDraftDetail() {
        assertThat(service.get(DRAFT_NOTICE_SN, admin()).isPublished()).isFalse();
    }

    @Test
    @DisplayName("★관리자는_DRAFT_공지에_첨부를_올린다")
    void adminUploadsAttachOnDraft() {
        assertThatCode(() -> attachService.upload(DRAFT_NOTICE_SN, upload(), admin()))
                .doesNotThrowAnyException();

        verify(attachRepository).save(any(LsNoticeAttach.class));
    }

    @Test
    @DisplayName("★관리자는_DRAFT_공지의_첨부를_다운로드한다")
    void adminDownloadsAttachOnDraft() {
        NoticeAttachService.Download download =
                attachService.download(DRAFT_NOTICE_SN, ATTACH_SN, admin());

        assertThat(download.fileName()).isEqualTo("첨부.pdf");
    }

    @Test
    @DisplayName("★관리자는_DRAFT_공지의_첨부를_삭제한다")
    void adminDeletesAttachOnDraft() {
        attachService.delete(DRAFT_NOTICE_SN, ATTACH_SN, admin());

        verify(attachRepository).delete(any(LsNoticeAttach.class));
    }

    // ---------- 관리자 쓰기 동선 — 등록·수정·발행·발행취소·삭제 ----------

    @Test
    @DisplayName("★관리자가_등록한_공지는_DRAFT이고_등록자로_관리자_sub가_기록된다")
    void adminCreatesNotice() {
        when(noticeRepository.save(any(LsNotice.class))).thenAnswer(inv -> inv.getArgument(0));

        LsNotice created = service.create("관리자 공지", "본문", true, admin());

        assertThat(created.isPublished()).isFalse();
        assertThat(created.isPinned()).isTrue();
        assertThat(created.getRegId()).isEqualTo("969300041");
    }

    @Test
    @DisplayName("★관리자가_공지를_수정하고_발행하고_발행취소하고_삭제한다")
    void adminUpdatesPublishesUnpublishesAndDeletes() {
        LsNotice updated = service.update(DRAFT_NOTICE_SN, "제목수정", "본문수정", true, admin());
        assertThat(updated.getTitle()).isEqualTo("제목수정");
        assertThat(updated.getMdfrId()).isEqualTo("969300041");

        assertThat(service.publish(DRAFT_NOTICE_SN).isPublished()).isTrue();
        assertThat(service.unpublish(DRAFT_NOTICE_SN).isPublished()).isFalse();

        when(noticeRepository.existsById(DRAFT_NOTICE_SN)).thenReturn(true);
        service.delete(DRAFT_NOTICE_SN);
        verify(noticeRepository).deleteById(DRAFT_NOTICE_SN);
    }

    // ---------- 대조군 — 기존 정책 불변 ----------

    @Test
    @DisplayName("검수자는_종전대로_DRAFT를_보고_첨부를_다룬다")
    void reviewerStillPasses() {
        TokenClaims reviewer = internal("1", Role.REVIEWER);

        assertThat(capturePublishedOnly(reviewer)).isFalse();
        assertThat(service.get(DRAFT_NOTICE_SN, reviewer).isPublished()).isFalse();
        assertThatCode(() -> attachService.delete(DRAFT_NOTICE_SN, ATTACH_SN, reviewer))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("작업자는_DRAFT_상세가_404이고_목록에서_DRAFT가_제외된다")
    void workerStillBlockedOnDraft() {
        TokenClaims worker = internal("2", Role.WORKER);

        assertThat(capturePublishedOnly(worker)).isTrue();
        assertThatThrownBy(() -> service.get(DRAFT_NOTICE_SN, worker))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("작업자는_DRAFT_첨부를_다루지_못하고_첨부_리포지토리에_닿지도_않는다")
    void workerCannotTouchDraftAttach() {
        TokenClaims worker = internal("2", Role.WORKER);

        assertThatThrownBy(() -> attachService.download(DRAFT_NOTICE_SN, ATTACH_SN, worker))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> attachService.delete(DRAFT_NOTICE_SN, ATTACH_SN, worker))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> attachService.upload(DRAFT_NOTICE_SN, upload(), worker))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));

        // ★ 부수효과 부재 — 가시성 게이트에서 끝났음을 고정한다(상태코드만 보면 헛돈다).
        verify(attachRepository, never()).findByAttachSnAndNoticeSn(anyLong(), anyLong());
        verify(attachRepository, never()).save(any(LsNoticeAttach.class));
        verify(attachRepository, never()).delete(any(LsNoticeAttach.class));
        // 업로드가 파일부터 쓰고 거부되지 않았음도 고정한다(고아 파일 방지).
        assertThat(storageRoot.resolve("notice-attach").toFile().listFiles())
                .containsExactly(storedFile.toFile());
    }

    @Test
    @DisplayName("포털_회원은_DRAFT가_404이고_목록에서도_DRAFT가_제외된다")
    void portalUserStillBlockedOnDraft() {
        TokenClaims portal = new TokenClaims("3", Role.PORTAL_USER, Channel.PORTAL,
                Instant.now().plusSeconds(600));

        assertThat(capturePublishedOnly(portal)).isTrue();
        assertThatThrownBy(() -> service.get(DRAFT_NOTICE_SN, portal))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("역할_미배정과_actor_null_은_fail_closed_다")
    void roleNullAndActorNullAreFailClosed() {
        assertThat(capturePublishedOnly(internal("42", null))).isTrue();
        assertThatThrownBy(() -> service.get(DRAFT_NOTICE_SN, internal("42", null)))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));

        // actor null — 정적 진입점을 쓰므로 NPE 가 아니라 거부다.
        assertThatThrownBy(() -> service.get(DRAFT_NOTICE_SN, null))
                .isInstanceOf(CustomException.class)
                .satisfies(t -> assertThat(errorCodeOf(t)).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("발행된_공지는_작업자도_상세와_첨부를_그대로_본다_읽기축_불변")
    void publishedNoticeStillReadableByWorker() {
        LsNotice published = LsNotice.create("발행 공지", "본문", false, "1");
        published.publish();
        ReflectionTestUtils.setField(published, "noticeSn", 9302L);
        when(noticeRepository.findById(9302L)).thenReturn(Optional.of(published));

        TokenClaims worker = internal("2", Role.WORKER);
        assertThat(service.get(9302L, worker).isPublished()).isTrue();
        assertThat(capturePublishedOnly(worker)).isTrue();
    }
}
