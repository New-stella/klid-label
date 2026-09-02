package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalTusCreateCommand;
import kr.co.cudo.authoring.portal.event.PortalVideoUploadedEvent;
import kr.co.cudo.authoring.portal.upload.PortalTusSessionRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.portal.service.PortalVideoProbe;
import kr.co.cudo.authoring.portal.service.PortalVideoUploadService;
import kr.co.cudo.authoring.portal.service.PortalVideoUploadTxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 TUS 영상 업로드 서비스 단위 테스트 — 완료·멱등·IDOR·매직바이트·오디오전용 방어.
 *
 * <p>리포지토리는 in-memory Map, 파일시스템은 {@link TempDir} 실디스크, ffprobe 는 stub 으로
 * 대체해 외부 의존 없이 결정적으로 검증한다.
 */
class PortalVideoUploadServiceTest {

    private static final String OWNER = "portal-user-1";
    private static final long MAX_SIZE = 5_368_709_120L;
    /** mp4 ISO BMFF 시그니처(4~8바이트 ftyp, brand mp42). */
    private static final byte[] MP4_HEAD = new byte[]{
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0};

    @TempDir
    Path storageDir;

    private InMemoryTusRepo tusRepository;
    private PortalUploadAssetRepository assetRepository;
    private ApplicationEventPublisher eventPublisher;
    private PortalVideoUploadService service;
    private final AtomicLong uldSnSeq = new AtomicLong(500);

    @BeforeEach
    void setUp() {
        tusRepository = new InMemoryTusRepo();
        assetRepository = mock(PortalUploadAssetRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        // 흡수 뒤 완료 합류처는 <포털 자산 적재>다 — 영상 원장 행 + 메타 몇 칸을 한 번에 만든다.
        when(assetRepository.insertUploaded(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> uldSnSeq.incrementAndGet());
        // 비디오 스트림 존재 + 60초 + 30fps 반환 stub.
        PortalVideoProbe probe = path -> new PortalVideoProbe.Result(true, 60.0, 30.0);
        service = build(probe);
    }

    private PortalVideoUploadService build(PortalVideoProbe probe) {
        PortalUploadProperties props = new PortalUploadProperties(
                MAX_SIZE, List.of("mp4", "mov", "avi"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 20_971_520L, 50, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        PortalVideoUploadTxService txService = new PortalVideoUploadTxService(
                tusRepository, assetRepository, props, eventPublisher);
        return new PortalVideoUploadService(tusRepository, props, probe, txService);
    }

    private PortalTusCreateCommand cmd(long len) {
        return new PortalTusCreateCommand(len, "myvideo.mp4");
    }

    // ======================== 완료 + 이벤트 ========================

    @Test
    @DisplayName("TUS_업로드_완료시_UPLOADED_상태와_이벤트_발행")
    void completesToUploadedAndPublishesEvent() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);

        assertThat(r.completed()).isTrue();
        assertThat(r.uldSn()).isNotNull();
        // 자산은 영상 원장의 포털 출처 행으로 적재된다(업로드됨 상태를 함께 기록).
        verify(assetRepository).insertUploaded(eq(OWNER), any(), eq("myvideo.mp4"), eq("video/mp4"), eq(16L));
        verify(eventPublisher).publishEvent(eq(new PortalVideoUploadedEvent(r.uldSn())));
        assertThat(tusRepository.findById(id).orElseThrow().isCompleted()).isTrue();
    }

    @Test
    @DisplayName("완료_PATCH_중복시_이벤트_1회만_발행")
    void duplicateCompletionPublishesEventOnce() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);
        assertThat(first.completed()).isTrue();

        // 마지막 청크 재전송 — 이미 COMPLETED → 멱등 응답, 이벤트 재발행 없음.
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full), 16);

        assertThat(again.completed()).isTrue();
        assertThat(again.uldSn()).isEqualTo(first.uldSn());
        verify(eventPublisher, times(1)).publishEvent(any(PortalVideoUploadedEvent.class));
    }

    // ======================== IDOR (#3) ========================

    /**
     * ★ 소유자 불일치는 <b>404</b> 다 (구 403 폐기 — 존재 오라클 차단, CWE-209).
     *
     * <p>403 은 "그 세션은 있는데 네 것이 아니다"를 알려줘 응답 자체가 세션 실재 여부를 확인해 주는
     * 통로가 된다. 거부는 offset·완료 검사보다 먼저라 <b>청크가 한 바이트도 기록되지 않는다</b>.
     */
    @Test
    @DisplayName("타사용자_TUS_세션에_PATCH시_404_이면서_청크도_기록되지_않는다")
    void nonOwnerPatchNotFound() throws Exception {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        Path temp = Path.of(tusRepository.findById(id).orElseThrow().getFilePath());

        assertThatThrownBy(() -> service.appendChunk(id, "intruder", 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        // 응답만 막고 바이트가 들어가면 IDOR 이 성립한다 — 오프셋·파일 둘 다 그대로여야 한다.
        assertThat(tusRepository.findById(id).orElseThrow().getUploadOffset()).isZero();
        assertThat(Files.size(temp)).isZero();
    }

    @Test
    @DisplayName("타사용자_TUS_세션에_HEAD_DELETE도_404")
    void nonOwnerHeadDeleteNotFound() {
        UUID id = service.createSession(OWNER, cmd(16));
        assertThatThrownBy(() -> service.getForOwner(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThatThrownBy(() -> service.cancel(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    /**
     * ★ 핵심 가드 — <b>소유자 불일치와 미존재가 구분 불가능</b>해야 한다(포털 TUS 3경로 전부).
     *
     * <p>코드만 404 로 맞추고 메시지가 다르면("본인의 업로드 세션이 아닙니다") 오라클이 <b>메시지로
     * 옮겨갔을 뿐</b> 그대로 남는다. 그래서 상태코드와 메시지를 <b>둘 다</b> 대조한다.
     */
    @Test
    @DisplayName("HEAD_소유자불일치와_미존재는_상태코드도_메시지도_동일하다")
    void headOwnerMismatchIndistinguishableFromMissing() {
        UUID existing = service.createSession(OWNER, cmd(16));
        UUID missing = UUID.randomUUID();

        CustomException byIntruder = catchCustomException(() -> service.getForOwner(existing, "intruder"));
        CustomException byMissing = catchCustomException(() -> service.getForOwner(missing, OWNER));

        assertThat(byIntruder.getErrorCode()).isEqualTo(byMissing.getErrorCode());
        assertThat(byIntruder.getMessage())
                .as("메시지가 다르면 오라클이 코드에서 메시지로 옮겨간 것일 뿐이다")
                .isEqualTo(byMissing.getMessage());
    }

    @Test
    @DisplayName("DELETE_소유자불일치와_미존재는_상태코드도_메시지도_동일하다")
    void cancelOwnerMismatchIndistinguishableFromMissing() {
        UUID existing = service.createSession(OWNER, cmd(16));
        UUID missing = UUID.randomUUID();

        CustomException byIntruder = catchCustomException(() -> service.cancel(existing, "intruder"));
        CustomException byMissing = catchCustomException(() -> service.cancel(missing, OWNER));

        assertThat(byIntruder.getErrorCode()).isEqualTo(byMissing.getErrorCode());
        assertThat(byIntruder.getMessage()).isEqualTo(byMissing.getMessage());
    }

    @Test
    @DisplayName("PATCH_소유자불일치와_미존재는_상태코드도_메시지도_동일하다")
    void patchOwnerMismatchIndistinguishableFromMissing() {
        byte[] full = mp4(16);
        UUID existing = service.createSession(OWNER, cmd(16));
        UUID missing = UUID.randomUUID();

        CustomException byIntruder = catchCustomException(() -> service.appendChunk(
                existing, "intruder", 0, new ByteArrayInputStream(full), 16));
        CustomException byMissing = catchCustomException(() -> service.appendChunk(
                missing, OWNER, 0, new ByteArrayInputStream(full), 16));

        assertThat(byIntruder.getErrorCode()).isEqualTo(byMissing.getErrorCode());
        assertThat(byIntruder.getMessage()).isEqualTo(byMissing.getMessage());
    }

    /** {@link CustomException} 만 잡아 반환 — 다른 예외면 테스트가 그대로 실패한다. */
    private CustomException catchCustomException(Runnable action) {
        try {
            action.run();
        } catch (CustomException e) {
            return e;
        }
        throw new AssertionError("CustomException 이 발생하지 않았다 — 거부되지 않았다는 뜻이다");
    }

    @Test
    @DisplayName("취소된_세션에_PATCH시_거부")
    void cancelledSessionPatchRejected() {
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));
        service.cancel(id, OWNER);

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ======================== 완료 검증 (#6) ========================

    @Test
    @DisplayName("영상_매직바이트_불일치시_완료_거부되고_임시파일_삭제")
    void magicByteMismatchRejectsAndDeletes() {
        byte[] bogus = new byte[16];
        for (int i = 0; i < 16; i++) bogus[i] = 'X';
        UUID id = service.createSession(OWNER, cmd(16));

        // item 6: 입력 검증 실패 → 400(INVALID_INPUT).
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(bogus), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        String filePath = tusRepository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("오디오만_있는_파일_완료시_400")
    void audioOnlyRejected() {
        // 매직바이트는 통과(mp4 컨테이너)하지만 비디오 스트림이 없는 파일 → 400(입력 검증 실패).
        PortalVideoProbe audioOnly = path -> new PortalVideoProbe.Result(false, 30.0, 0.0);
        service = build(audioOnly);
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        String filePath = tusRepository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("검증_거부시_세션이_CANCELLED로_영속되고_파일_삭제")
    void rejectionPersistsCancelledAndDeletesFile() {
        // security M-2: 거부는 독립 tx 로 CANCELLED 를 커밋 — 400 이 롤백시켜 IN_PROGRESS 고착되지 않음.
        PortalVideoProbe audioOnly = path -> new PortalVideoProbe.Result(false, 30.0, 0.0);
        service = build(audioOnly);
        byte[] full = mp4(16);
        UUID id = service.createSession(OWNER, cmd(16));

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full), 16))
                .isInstanceOf(CustomException.class);

        LsTusUpload session = tusRepository.findById(id).orElseThrow();
        assertThat(session.isCancelled()).isTrue();
        assertThat(session.isCompleted()).isFalse();
        assertThat(Files.exists(Path.of(session.getFilePath()))).isFalse();
    }

    // ======================== 경계/상한 ========================

    @Test
    @DisplayName("UploadLength가_maxFileSize초과시_413")
    void uploadLengthTooLarge() {
        assertThatThrownBy(() -> service.createSession(OWNER, cmd(MAX_SIZE + 1)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("허용안된_확장자_세션생성시_400")
    void invalidExtensionRejected() {
        assertThatThrownBy(() -> service.createSession(OWNER, new PortalTusCreateCommand(16, "evil.exe")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("경로순회_파일명이어도_저장은_UUID강제_storage내부")
    void pathTraversalForcedUuid() {
        UUID id = service.createSession(OWNER, new PortalTusCreateCommand(16, "../../../etc/passwd.mp4"));
        String filePath = tusRepository.findById(id).orElseThrow().getFilePath();
        assertThat(filePath).contains(id.toString());
        assertThat(filePath).doesNotContain("etc/passwd");
        assertThat(Path.of(filePath).normalize().startsWith(storageDir.toAbsolutePath().normalize())).isTrue();
    }

    // ======================== 헬퍼 ========================

    private static byte[] mp4(int size) {
        byte[] out = new byte[Math.max(size, MP4_HEAD.length)];
        System.arraycopy(MP4_HEAD, 0, out, 0, Math.min(MP4_HEAD.length, out.length));
        return out;
    }

    /** 최소 in-memory 구현 — 테스트에 필요한 메서드만. 세션 원장은 이제 공용({@code LS_TUS_UPLOAD})이다. */
    static class InMemoryTusRepo implements PortalTusSessionRepository {
        final Map<UUID, LsTusUpload> store = new HashMap<>();

        @Override
        public long countInProgressByOwner(String portalUserNo) {
            return store.values().stream()
                    .filter(u -> portalUserNo.equals(u.getUserNo())
                            && LsTusUpload.STATUS_IN_PROGRESS.equals(u.getStatus()))
                    .count();
        }

        @Override
        public Optional<LsTusUpload> findPortalSession(UUID uploadId) {
            return Optional.ofNullable(store.get(uploadId));
        }

        @Override
        public Optional<LsTusUpload> findPortalSessionForUpdate(UUID uploadId) {
            return Optional.ofNullable(store.get(uploadId));
        }

        @Override
        public int markCompletedIfInProgress(UUID uploadId, Long rawSn, LocalDateTime now) {
            LsTusUpload u = store.get(uploadId);
            if (u == null || !LsTusUpload.STATUS_IN_PROGRESS.equals(u.getStatus())) {
                return 0;
            }
            u.markCompleted(rawSn);
            return 1;
        }

        @Override
        public int deleteExpiredInProgress(UUID uploadId) {
            LsTusUpload u = store.get(uploadId);
            if (u == null || !LsTusUpload.STATUS_IN_PROGRESS.equals(u.getStatus())) {
                return 0;
            }
            store.remove(uploadId);
            return 1;
        }

        @Override
        public List<LsTusUpload> findExpired(LocalDateTime now) {
            return store.values().stream()
                    .filter(u -> LsTusUpload.STATUS_IN_PROGRESS.equals(u.getStatus())
                            && u.getExpiresAt().isBefore(now))
                    .toList();
        }

        @Override
        public <S extends LsTusUpload> S save(S entity) {
            store.put(entity.getUploadId(), entity);
            return entity;
        }

        @Override
        public <S extends LsTusUpload> S saveAndFlush(S entity) {
            store.put(entity.getUploadId(), entity);
            return entity;
        }

        @Override
        public Optional<LsTusUpload> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public void delete(LsTusUpload entity) {
            store.remove(entity.getUploadId());
        }

        // ----- 미사용 JpaRepository 메서드 -----
        @Override public List<LsTusUpload> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<LsTusUpload> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public List<LsTusUpload> findAllById(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends LsTusUpload> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<LsTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getOne(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getReferenceById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<LsTusUpload> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends LsTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { store.clear(); }
    }
}
