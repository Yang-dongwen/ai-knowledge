package com.dwcode.okxbot.aigen;

import com.dwcode.okxbot.aigen.domain.SceneDto;
import com.dwcode.okxbot.aigen.domain.StoryboardDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlexibleScenePropsDeserializerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsObjectProps() throws Exception {
        String json = """
                {"id":"s1","type":"hook","narration":"hi","props":{"eyebrow":"标签","title":"标题","subtitle":"副"}}
                """;
        SceneDto s = mapper.readValue(json, SceneDto.class);
        assertEquals("标签", s.getProps().getEyebrow());
        assertEquals("标题", s.getProps().getTitle());
        assertEquals("副", s.getProps().getSubtitle());
    }

    @Test
    void acceptsKeyEqualsArrayProps() throws Exception {
        // 用户报错形态
        String json = """
                {
                  "version":"1.0",
                  "meta":{"templateId":"insight-compare","fps":30,"width":1080,"height":1920},
                  "scenes":[{
                    "id":"1",
                    "type":"hook",
                    "narration":"学AI到底该从哪下手？",
                    "props":[
                      "eyebrow=学AI避坑指南",
                      "title=先追模型还是先锁场景？",
                      "subtitle=三十秒给你讲透"
                    ]
                  }]
                }
                """;
        StoryboardDto sb = mapper.readValue(json, StoryboardDto.class);
        assertEquals(1, sb.getScenes().size());
        assertEquals("学AI避坑指南", sb.getScenes().get(0).getProps().getEyebrow());
        assertEquals("先追模型还是先锁场景？", sb.getScenes().get(0).getProps().getTitle());
        assertEquals("三十秒给你讲透", sb.getScenes().get(0).getProps().getSubtitle());
    }

    @Test
    void acceptsCompareArrayProps() throws Exception {
        String json = """
                {"id":"s2","type":"compare","narration":"对比",
                 "props":["heading=两种路径","leftLabel=无效","rightLabel=有效",
                          "leftItems=堆概念|无验收","rightItems=锁场景|最小闭环"]}
                """;
        SceneDto s = mapper.readValue(json, SceneDto.class);
        assertEquals("两种路径", s.getProps().getHeading());
        assertEquals("无效", s.getProps().getLeftLabel());
        assertEquals(2, s.getProps().getLeftItems().size());
        assertEquals("锁场景", s.getProps().getRightItems().get(0));
    }
}
