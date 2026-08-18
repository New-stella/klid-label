package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.entity.LsPortalUld;
import kr.co.cudo.authoring.portal.entity.LsPortalUldFrme;
import kr.co.cudo.authoring.portal.repository.LsPortalUldFrmeRepository;
import kr.co.cudo.authoring.portal.repository.LsPortalUldRepository;
import kr.co.cudo.authoring.portal.service.PortalUploadService;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 자산 <b>삭제</b>의 실경로 봉쇄 회귀 가드 (CWE-59/367). @design AC-037
 *
 * <h3>이 파일이 막는 것</h3>
 * <p>삭제 경로는 lexical 검증({@code resolveSafe})만 통과하면 <b>그 lexical 경로로</b> 파일을 지우고
 * 있었다. 그 사이에 경로를 심링크로 갈아끼우면 저장 루트 <b>밖</b> 파일이 지워진다. 삭제는 열람보다
 * 위험하다 — 잘못 열면 유출이지만 잘못 지우면 되돌릴 수 없다.
 *
 * <h3>★ leaf 링크로는 이 결함을 재현할 수 없다 (가드가 헛돌게 되는 지점)</h3>
 * <p>{@code Files.delete}/{@code deleteIfExists} 는 <b>최종 경로 요소가 링크면 링크 자체만</b> 지우고
 * 대상은 건드리지 않는다. 그래서 "base 안의 파일이 base 밖을 가리키는 심링크" 로 만든 테스트는
 * <b>수정 전에도 통과</b>해 아무것도 지키지 못한다. 실제로 따라가는 것은 <b>경로 중간의 디렉터리
 * 링크</b>이므로 이 파일의 주 시나리오는 그 축이다.
 */
class PortalUploadDeleteRealPathGuardTest {

    private static final String ALICE = "alice";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};

    /** 저작도구 저장 루트. */
    @TempDir Path storageDir;
    /** 저장 루트 <b>밖</b> — 지워져서는 안 되는 영역(다른 도메인의 원본 프레임 자리). */
    @TempDir Path outsideDir;

    private LsPortalUldRepository uldRepository;
    private LsPortalUldFrmeRepository frmeRepository;
    private PortalUploadService service;

    @BeforeEach
    void setUp() {
        uldRepository = mock(LsPortalUldRepository.class);
        frmeRepository = mock(LsPortalUldFrmeRepository.class);
        PortalUploadProperties props = new PortalUploadProperties(
                5_368_709_120L, List.of("mp4", "mov", "avi"), storageDir.toString(),
                List.of("jpg", "jpeg", "png"), 1024L, 3, 2000,
                16_777_216L, 2_097_152L, 30L, 30L);
        service = new PortalUploadService(uldRepository, frmeRepository, props,
                mock(kr.co.cudo.authoring.portal.repository.LsPortalUldLblRepository.class),
                new kr.co.cudo.authoring.portal.service.PortalRetentionPolicy(
                        mock(kr.co.cudo.authoring.sysconfig.service.SystemConfigService.class)),
                new kr.co.cudo.authoring.portal.service.PortalStoragePathGuard(props));
    }

    // ------------------------------------------------------------------ 중간 디렉터리 심링크

    @Test
    @DisplayName("경로_중간_디렉터리가_저장루트_밖을_가리키면_삭제가_거부되고_대상파일이_살아있다")
    void deleteRejectedWhenIntermediateDirectoryEscapesBase() throws Exception {
        // given — 저장 루트 밖의 희생 파일. 이 파일이 사라지면 그 자체로 비가역 데이터 유실이다.
        Path victimDir = Files.createDirectories(outsideDir.resolve("frames"));
        Path victim = Files.write(victimDir.resolve("victim.jpg"), JPEG);

        // given — base 안의 디렉터리를 통째로 밖을 가리키는 링크로 만든다.
        //   lexical 로는 base/images/... 라 resolveSafe 를 그대로 통과한다.
        Path imagesDir = Files.createDirectories(storageDir.resolve("images"));
        Files.createSymbolicLink(imagesDir.resolve("escape"), victimDir);
        Path lexicalPath = imagesDir.resolve("escape").resolve("victim.jpg");

        seedOwnedUpload(42L, lexicalPath);

        // when — 예외를 먼저 단언하면 거기서 멈춰 <실제 유실>이 보고에 드러나지 않는다.
        //   무엇이 깨졌는지가 중요하므로 호출 결과를 담아 두고 세 축을 함께 본다.
        Throwable raised = catchThrowable(() -> service.deleteUpload(42L, ALICE));

        // then
        SoftAssertions.assertSoftly(soft -> {
            soft.assertThat(Files.exists(victim))
                    .as("★저장 루트 밖 파일이 지워졌다 — 중간 디렉터리 링크는 delete 가 그대로 따라간다")
                    .isTrue();
            soft.assertThat(raised)
                    .as("조용히 넘어가서도 안 된다 — 지우지 못했으면 중단해야 한다")
                    .isInstanceOf(CustomException.class);
        });
        verify(uldRepository, never()).delete(any());
    }

    @Test
    @DisplayName("판정_실패시_DB행을_보존한다_파일만_남고_행이_사라지면_추적_불가")
    void dbRowPreservedWhenVerdictIsNotOk() throws Exception {
        Path victimDir = Files.createDirectories(outsideDir.resolve("frames"));
        Files.write(victimDir.resolve("v.jpg"), JPEG);
        Path imagesDir = Files.createDirectories(storageDir.resolve("images"));
        Files.createSymbolicLink(imagesDir.resolve("escape"), victimDir);

        seedOwnedUpload(43L, imagesDir.resolve("escape").resolve("v.jpg"));

        assertThatThrownBy(() -> service.deleteUpload(43L, ALICE))
                .isInstanceOf(CustomException.class);
        verify(uldRepository, never()).delete(any());
    }

    // ------------------------------------------------------------------ 멱등 (toRealPath 함정)

    @Test
    @DisplayName("이미_없는_파일의_삭제는_예외없이_멱등하게_끝난다")
    void deleteOfAlreadyMissingFileIsIdempotent() throws Exception {
        // given — DB 행은 있으나 물리 파일이 이미 없다(앞선 삭제가 DB 커밋 전에 끊긴 경우 등).
        //   toRealPath() 는 deleteIfExists 와 달리 대상이 없으면 예외를 던지므로, 부재를 먼저
        //   분기하지 않으면 그 자산은 영영 삭제할 수 없게 된다.
        Path ghost = storageDir.resolve("images").resolve("ghost.jpg");
        seedOwnedUpload(44L, ghost);

        // when / then
        assertThatCode(() -> service.deleteUpload(44L, ALICE)).doesNotThrowAnyException();
        verify(uldRepository).delete(any());
    }

    // ------------------------------------------------------------------ 정상 경로 (계약 보존)

    @Test
    @DisplayName("저장루트_안의_정상_파일은_기존대로_삭제된다")
    void normalDeleteStillWorks() throws Exception {
        Path imagesDir = Files.createDirectories(storageDir.resolve("images"));
        Path file = Files.write(imagesDir.resolve("ok.jpg"), JPEG);
        seedOwnedUpload(45L, file);

        service.deleteUpload(45L, ALICE);

        assertThat(Files.exists(file)).isFalse();
        verify(uldRepository).delete(any());
    }

    // ------------------------------------------------------------------ 내부

    /** 소유자 자산 1건 + 그 자산의 프레임 1건이 같은 경로를 가리키는 형상(이미지 업로드와 동일). */
    private void seedOwnedUpload(long uldSn, Path filePath) {
        LsPortalUld uld = LsPortalUld.createImage(
                ALICE, "x.jpg", filePath.toString(), 4L, "image/jpeg");
        setField(uld, "uldSn", uldSn);
        LsPortalUldFrme frme = LsPortalUldFrme.create(uldSn, 0, filePath.toString());
        when(uldRepository.findByUldSnAndPortalUserNo(uldSn, ALICE)).thenReturn(Optional.of(uld));
        when(frmeRepository.findAllByUldSnOrderByFrmeNo(uldSn)).thenReturn(List.of(frme));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("테스트 픽스처 주입 실패: " + name, e);
        }
    }
}
