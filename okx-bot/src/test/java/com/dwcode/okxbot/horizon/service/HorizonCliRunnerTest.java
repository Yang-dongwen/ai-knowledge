package com.dwcode.okxbot.horizon.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizonCliRunnerTest {

    @TempDir
    Path dir;

    @Test
    void commandIncludesHours() {
        List<String> cmd = new HorizonCliRunner().command(3);
        assertTrue(cmd.contains("uv"));
        assertTrue(cmd.contains("horizon"));
        assertEquals("3", cmd.get(cmd.indexOf("--hours") + 1));
    }

    @Test
    void detectsHorizonRoot() throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), "[project]\nname='x'\n");
        Files.createDirectory(dir.resolve("src"));
        assertTrue(HorizonCliRunner.isHorizonRoot(dir));
    }
}
