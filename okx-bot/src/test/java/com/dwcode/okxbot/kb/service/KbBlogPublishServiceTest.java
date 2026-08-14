package com.dwcode.okxbot.kb.service;

import com.dwcode.okxbot.auth.security.SecurityUtils;
import com.dwcode.okxbot.blog.port.HaloPublishCommand;
import com.dwcode.okxbot.blog.port.HaloPublishPort;
import com.dwcode.okxbot.blog.port.HaloPublishResult;
import com.dwcode.okxbot.common.exception.BusinessException;
import com.dwcode.okxbot.kb.dto.NoteResponse;
import com.dwcode.okxbot.kb.entity.KbNoteEntity;
import com.dwcode.okxbot.kb.mapper.KbNoteMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbBlogPublishServiceTest {

    @Test
    void hasPrivateMedia() {
        assertTrue(KbBlogPublishService.hasPrivateMedia("<img src=\"/api/v1/kb/files/1/content\">"));
        assertEquals(false, KbBlogPublishService.hasPrivateMedia("# hi"));
    }

    @Test
    void writesBackHaloFields() {
        KbNoteMapper mapper = mock(KbNoteMapper.class);
        KbNoteService noteService = mock(KbNoteService.class);
        HaloPublishPort port = mock(HaloPublishPort.class);

        KbNoteEntity e = new KbNoteEntity();
        e.setId(11L);
        e.setUserId(7L);
        e.setTitle("标题");
        e.setContent("# hi");
        e.setContentFormat("markdown");
        e.setIsDeleted(0);
        when(mapper.selectById(11L)).thenReturn(e);
        when(port.publish(any())).thenReturn(
                new HaloPublishResult("post-x", "https://blog.example.com/a", "/a"));
        when(noteService.get(11L)).thenReturn(NoteResponse.builder().id(11L).title("标题").build());

        KbBlogPublishService svc = new KbBlogPublishService(mapper, noteService, port);
        try (MockedStatic<SecurityUtils> st = mockStatic(SecurityUtils.class)) {
            st.when(SecurityUtils::requireCurrentUserId).thenReturn(7L);
            NoteResponse resp = svc.publish(11L);
            assertEquals("标题", resp.getTitle());
        }

        ArgumentCaptor<HaloPublishCommand> cap = ArgumentCaptor.forClass(HaloPublishCommand.class);
        verify(port).publish(cap.capture());
        assertEquals("标题", cap.getValue().title());
        assertEquals("markdown", cap.getValue().rawType());
        verify(mapper).updateById(e);
        assertEquals("post-x", e.getHaloPostName());
        assertEquals("https://blog.example.com/a", e.getHaloPermalink());
    }

    @Test
    void rejectsOtherUsersNote() {
        KbNoteMapper mapper = mock(KbNoteMapper.class);
        KbNoteEntity e = new KbNoteEntity();
        e.setId(2L);
        e.setUserId(99L);
        e.setIsDeleted(0);
        when(mapper.selectById(2L)).thenReturn(e);

        KbBlogPublishService svc = new KbBlogPublishService(
                mapper, mock(KbNoteService.class), mock(HaloPublishPort.class));
        try (MockedStatic<SecurityUtils> st = mockStatic(SecurityUtils.class)) {
            st.when(SecurityUtils::requireCurrentUserId).thenReturn(1L);
            assertThrows(BusinessException.class, () -> svc.publish(2L));
        }
    }
}
