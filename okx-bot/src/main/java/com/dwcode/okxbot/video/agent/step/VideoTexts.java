package com.dwcode.okxbot.video.agent.step;

public final class VideoTexts {

    private VideoTexts() {
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
