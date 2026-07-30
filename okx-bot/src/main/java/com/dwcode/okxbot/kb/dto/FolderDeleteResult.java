package com.dwcode.okxbot.kb.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FolderDeleteResult {
    private int foldersDeleted;
    private int notesOrphaned;
    private int notesTrashed;
}
