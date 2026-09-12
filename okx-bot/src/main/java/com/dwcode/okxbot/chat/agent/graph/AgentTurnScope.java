package com.dwcode.okxbot.chat.agent.graph;

import com.dwcode.okxbot.chat.stream.StreamCancelledException;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 单轮 Agent 的 cancel / phase 回调（请求线程 ThreadLocal，避免污染单例 Node）。
 */
public final class AgentTurnScope {

    private static final ThreadLocal<Ctx> TL = new ThreadLocal<>();

    private AgentTurnScope() {
    }

    public static void open(Supplier<Boolean> cancel, Consumer<String> phase) {
        TL.set(new Ctx(
                cancel != null ? cancel : () -> false,
                phase != null ? phase : p -> {}));
    }

    public static void close() {
        TL.remove();
    }

    public static void emit(String phase) {
        Ctx c = TL.get();
        if (c == null || c.phase == null) {
            return;
        }
        try {
            c.phase.accept(phase);
        } catch (Exception ignored) {
            // ignore
        }
    }

    public static void throwIfCancelled() {
        Ctx c = TL.get();
        if (c == null) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(c.cancel.get())) {
                throw new StreamCancelledException("");
            }
        } catch (StreamCancelledException e) {
            throw e;
        } catch (Exception ignored) {
            // ignore
        }
    }

    private record Ctx(Supplier<Boolean> cancel, Consumer<String> phase) {
    }
}
