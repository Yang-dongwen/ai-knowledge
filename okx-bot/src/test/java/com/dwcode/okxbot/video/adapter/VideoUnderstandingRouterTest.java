package com.dwcode.okxbot.video.adapter;

import com.dwcode.okxbot.video.adapter.NvidiaOmniVideoAdapter.MediaOmniException;
import com.dwcode.okxbot.video.adapter.VideoUnderstandingRouter.WindowResult;
import com.dwcode.okxbot.video.config.VideoProperties;
import com.dwcode.okxbot.video.port.VideoUnderstandingCommand;
import com.dwcode.okxbot.video.port.VideoUnderstandingProtocol;
import com.dwcode.okxbot.video.port.VisualUnderstandingResult.ChunkUnderstanding;
import com.dwcode.okxbot.video.service.MediaChunkPrepareService;
import com.dwcode.okxbot.video.service.MediaChunkPrepareService.TimeWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoUnderstandingRouterTest {

    @Mock
    private NvidiaOmniVideoAdapter omniAdapter;
    @Mock
    private FrameSampleVlmAdapter frameSampleVlmAdapter;
    @Mock
    private MockVideoUnderstandingAdapter mockAdapter;
    @Mock
    private MediaChunkPrepareService mediaChunkPrepareService;

    private VideoProperties videoProperties;
    private VideoUnderstandingRouter router;

    @BeforeEach
    void setUp() {
        videoProperties = new VideoProperties();
        videoProperties.getUnderstanding().setFallbackFrameVlm(true);
        videoProperties.getUnderstanding().setProtocol("auto");
        router = new VideoUnderstandingRouter(
                omniAdapter, frameSampleVlmAdapter, mockAdapter,
                mediaChunkPrepareService, videoProperties);
    }

    @Test
    void resolveProtocolPrefersCommand() {
        VideoUnderstandingCommand cmd = VideoUnderstandingCommand.builder()
                .protocol("frame-vlm")
                .build();
        assertEquals(VideoUnderstandingProtocol.FRAME, router.resolveProtocol(cmd));
    }

    @Test
    void configMockWins() {
        videoProperties.getUnderstanding().setMock(true);
        assertTrue(router.isMock("auto"));
    }

    @Test
    void frameProtocolNeverCallsOmni() throws Exception {
        stubFrame();
        WindowResult r = router.understandWindow(
                "frame-vlm", cmd(), Path.of("v.mp4"), new TimeWindow(0, 10), 0, "asr");
        assertEquals(VideoUnderstandingProtocol.FRAME, r.protocolUsed());
        verify(omniAdapter, never()).understandChunk(
                any(), anyDouble(), anyDouble(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void omniSuccess() throws Exception {
        Path chunk = Path.of("c.mp4");
        when(mediaChunkPrepareService.extractChunk(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(chunk);
        ChunkUnderstanding ch = new ChunkUnderstanding();
        when(omniAdapter.understandChunk(eq(chunk), anyDouble(), anyDouble(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                anyBoolean(), nullable(String.class))).thenReturn(ch);

        WindowResult r = router.understandWindow(
                "auto", cmd(), Path.of("v.mp4"), new TimeWindow(0, 8), 0, null);
        assertEquals(VideoUnderstandingProtocol.OMNI, r.protocolUsed());
        assertEquals(ch, r.chunk());
        verify(frameSampleVlmAdapter, never()).understandFrames(
                any(), anyDouble(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    void omniMediaFailureFallsBackToFrame() throws Exception {
        when(mediaChunkPrepareService.extractChunk(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Path.of("c.mp4"));
        when(omniAdapter.understandChunk(any(), anyDouble(), anyDouble(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                anyBoolean(), nullable(String.class)))
                .thenThrow(new MediaOmniException("too large"));
        stubFrame();

        WindowResult r = router.understandWindow(
                "nvidia-omni-chat", cmd(), Path.of("v.mp4"), new TimeWindow(0, 8), 0, null);
        assertEquals(VideoUnderstandingProtocol.FRAME_FALLBACK, r.protocolUsed());
    }

    @Test
    void omniMediaFailureWithoutFallbackRethrows() throws Exception {
        videoProperties.getUnderstanding().setFallbackFrameVlm(false);
        when(mediaChunkPrepareService.extractChunk(anyString(), any(), any(), anyInt(), anyBoolean()))
                .thenReturn(Path.of("c.mp4"));
        when(omniAdapter.understandChunk(any(), anyDouble(), anyDouble(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                anyBoolean(), nullable(String.class)))
                .thenThrow(new MediaOmniException("too large"));

        assertThrows(MediaOmniException.class, () -> router.understandWindow(
                "auto", cmd(), Path.of("v.mp4"), new TimeWindow(0, 8), 0, null));
        verify(frameSampleVlmAdapter, never()).understandFrames(
                any(), anyDouble(), anyDouble(), any(), any(), any(), any());
    }

    private void stubFrame() throws Exception {
        when(mediaChunkPrepareService.extractFrames(anyString(), any(), any(), anyInt()))
                .thenReturn(List.of(Path.of("f.jpg")));
        when(frameSampleVlmAdapter.understandFrames(any(), anyDouble(), anyDouble(),
                nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class))).thenReturn(new ChunkUnderstanding());
    }

    private static VideoUnderstandingCommand cmd() {
        return VideoUnderstandingCommand.builder()
                .taskId("9")
                .language("zh")
                .providerKey("nvidia")
                .modelId("omni")
                .stripAudio(true)
                .useAudioInVideo(false)
                .build();
    }
}
