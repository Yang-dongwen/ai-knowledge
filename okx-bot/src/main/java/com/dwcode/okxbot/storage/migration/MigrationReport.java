package com.dwcode.okxbot.storage.migration;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class MigrationReport {

    private boolean dryRun;
    private boolean deleteLocal;
    private String storageProvider;
    private String envPrefix;

    @Builder.Default
    private List<ModuleReport> modules = new ArrayList<>();

    private long startedAtMs;
    private long finishedAtMs;

    @Data
    @Builder
    public static class ModuleReport {
        private String module;
        private int scannedDirs;
        private int tasksUpdated;
        private int filesUploaded;
        private int filesSkipped;
        private int filesFailed;
        private int localsDeleted;
        @Builder.Default
        private List<String> errors = new ArrayList<>();
        @Builder.Default
        private List<String> samples = new ArrayList<>();
    }
}
