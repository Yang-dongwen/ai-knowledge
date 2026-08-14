<template>
  <div
    class="kb-workspace"
    :class="{ 'mobile-detail': mobileDetailOpen }"
  >
    <!-- 左：目录树（文件夹 + 文档） -->
    <aside class="kb-sidebar">
      <div class="side-head">
        <span class="side-title">{{ trashMode ? '回收站' : '文档目录' }}</span>
        <div class="side-head-actions">
          <template v-if="trashMode">
            <a-button type="link" size="small" class="back-dir-btn" @click="toggleTrashMode">
              返回目录
            </a-button>
          </template>
          <template v-else>
            <a-tooltip title="新建文件夹">
              <a-button type="text" size="small" class="icon-action" @click="openCreateCategory()">
                <FolderOutlined />
              </a-button>
            </a-tooltip>
            <a-tooltip title="新建文档">
              <a-button type="text" size="small" class="icon-action" @click="openCreateNote">
                <PlusOutlined />
              </a-button>
            </a-tooltip>
          </template>
        </div>
      </div>

      <div class="side-search">
        <a-input-search
          v-model:value="keyword"
          allow-clear
          size="small"
          :placeholder="trashMode ? '搜索回收站…' : '搜索文档…'"
          @search="onSearch"
        />
      </div>

      <div v-if="!trashMode" class="quick-views">
        <button
          type="button"
          class="quick-chip"
          :class="{ on: listViewMode === 'recent' }"
          @click="openRecentList"
        >
          最近
        </button>
        <button
          type="button"
          class="quick-chip"
          :class="{ on: listViewMode === 'pinned' }"
          @click="openPinnedList"
        >
          置顶
        </button>
        <button
          v-if="listViewMode"
          type="button"
          class="quick-chip ghost"
          @click="clearListView"
        >
          目录
        </button>
      </div>

      <div
        class="side-scroll"
        :class="{
          'is-tree': isExplorerTreeMode,
          'is-list': !isExplorerTreeMode
        }"
      >
        <!-- 回收站 / 搜索 / 标签 / 最近 / 置顶：统一结果列表 -->
        <template v-if="!isExplorerTreeMode">
          <div v-if="!trashMode" class="result-list-head">
            <span class="result-list-title">
              <template v-if="searchMode">搜索 · {{ total }}</template>
              <template v-else-if="listViewMode === 'recent'">最近 · {{ total }}</template>
              <template v-else-if="listViewMode === 'pinned'">置顶 · {{ total }}</template>
              <template v-else>标签 · {{ total }}</template>
            </span>
            <button
              type="button"
              class="link-btn"
              @click="
                searchMode
                  ? clearSearch()
                  : listViewMode
                    ? clearListView()
                    : clearTagFilter()
              "
            >
              返回目录
            </button>
          </div>
          <div v-else-if="total > 0 || listLoading" class="result-list-head">
            <span class="result-list-title">共 {{ total }} 条</span>
          </div>
          <div v-if="listLoading" class="side-loading"><a-spin size="small" /></div>
          <!-- 回收站空列表：侧栏轻提示；主区域居中展示完整空态 -->
          <div
            v-else-if="!notes.length && trashMode"
            class="result-empty-side muted"
          >
            {{ keyword.trim() ? '无匹配文档' : '暂无已删除文档' }}
          </div>
          <div v-else-if="!notes.length" class="side-empty result-empty">
            <template v-if="searchMode">无匹配文档</template>
            <template v-else>该标签下暂无文档</template>
          </div>
          <div v-else class="result-list">
            <button
              v-for="n in notes"
              :key="(trashMode ? 'trash-' : searchMode ? 'search-' : 'tag-') + n.id"
              type="button"
              class="result-item"
              :class="{ active: selectedId === n.id, deleted: trashMode || n.deleted }"
              @click="selectNote(n.id)"
            >
              <div class="result-item-top">
                <FileTextOutlined class="result-item-icon" />
                <span class="result-item-title">{{ n.title || '未命名笔记' }}</span>
                <a-tag
                  v-if="n.contentFormat"
                  class="result-fmt"
                  :color="n.contentFormat === 'markdown' ? 'blue' : 'green'"
                >
                  {{ n.contentFormat === 'markdown' ? 'MD' : '富' }}
                </a-tag>
              </div>
              <div
                v-if="n.matchSnippet || n.snippet"
                class="result-item-snippet"
              >{{ n.matchSnippet || n.snippet }}</div>
              <div class="result-item-foot">
                <span v-if="n.categoryName" class="result-cat">{{ n.categoryName }}</span>
                <span
                  v-for="t in n.tags?.slice(0, 2) || []"
                  :key="t.id"
                  class="result-tag"
                >#{{ t.name }}</span>
                <span v-if="n.updatedAt" class="result-time">{{ formatListTime(n.updatedAt) }}</span>
              </div>
            </button>
          </div>
          <div v-if="trashMode && total > 0" class="trash-actions">
            <a-popconfirm
              title="清空回收站？将永久删除全部笔记及附件，不可恢复。"
              ok-text="清空"
              ok-type="danger"
              cancel-text="取消"
              @confirm="emptyTrash"
            >
              <a-button danger size="small" block :loading="emptying">清空回收站</a-button>
            </a-popconfirm>
          </div>
          <div v-if="total > pageSize" class="result-pager">
            <a-pagination
              size="small"
              :current="page + 1"
              :page-size="pageSize"
              :total="total"
              :show-size-changer="false"
              @change="onResultPageChange"
            />
          </div>
        </template>

        <!-- 目录树 -->
        <template v-else>
          <div v-if="treeLoading" class="side-loading"><a-spin size="small" /></div>
          <div v-else-if="!flatTreeRows.length" class="side-empty">
            还没有内容
            <div class="side-empty-actions">
              <a-button size="small" type="primary" @click="openCreateNote">新建文档</a-button>
              <a-button size="small" @click="openCreateCategory()">新建文件夹</a-button>
            </div>
          </div>
          <div v-else class="explorer-tree">
            <!-- 根投放区：移到根/未归档 -->
            <div
              class="tree-drop-root"
              :class="{ 'drop-over': dropTargetKey === 'root' }"
              @dragover.prevent="onDragOverRoot"
              @dragleave="onDragLeaveRoot"
              @drop.prevent="onDropRoot"
            >
              拖到此处 → 根目录 / 未归档
            </div>
            <!-- 虚拟滚动视口：只渲染可见行 -->
            <div
              ref="treeViewportRef"
              class="tree-viewport"
              @scroll.passive="onTreeScroll"
            >
              <div
                class="tree-virtual-space"
                :style="{ height: treeVirtual.totalHeight + 'px' }"
              >
                <div
                  class="tree-virtual-window"
                  :style="{ transform: `translateY(${treeVirtual.offsetY}px)` }"
                >
                  <div
                    v-for="row in virtualTreeRows"
                    :key="row.key"
                    class="tree-row-wrap"
                    :class="{
                      'drop-over':
                        dropTargetKey === row.key &&
                        (row.type === 'folder' ||
                          (dragPayload && dragPayload.type === row.type)),
                      dragging: dragPayload && dragPayload.key === row.key
                    }"
                    :style="{
                      height: TREE_ROW_HEIGHT + 'px',
                      paddingLeft: `${8 + row.depth * 14}px`
                    }"
                    draggable="true"
                    @dragstart="onTreeDragStart($event, row)"
                    @dragend="onTreeDragEnd"
                    @dragover.prevent="onTreeDragOver($event, row)"
                    @dragleave="onTreeDragLeave(row)"
                    @drop.prevent="onTreeDrop($event, row)"
                  >
                    <button
                      type="button"
                      class="tree-row"
                      :class="{
                        folder: row.type === 'folder',
                        note: row.type === 'note',
                        active:
                          (row.type === 'note' && selectedId === row.id) ||
                          (row.type === 'folder' &&
                            activeFolderId === row.id &&
                            !selectedId &&
                            !isCreating)
                      }"
                      @click="onTreeRowClick(row)"
                    >
                      <span
                        v-if="row.type === 'folder'"
                        class="tree-twist"
                        @click.stop="toggleExpand(row.id)"
                      >
                        <CaretDownOutlined v-if="expandedIds.has(row.id)" />
                        <CaretRightOutlined v-else />
                      </span>
                      <span v-else class="tree-twist spacer" />
                      <FolderOpenOutlined
                        v-if="row.type === 'folder' && expandedIds.has(row.id)"
                        class="tree-icon folder-icon"
                      />
                      <FolderOutlined
                        v-else-if="row.type === 'folder'"
                        class="tree-icon folder-icon"
                      />
                      <PushpinOutlined
                        v-else-if="row.pinned"
                        class="tree-icon pin-icon"
                      />
                      <FileTextOutlined v-else class="tree-icon note-icon" />
                      <span class="tree-label" :title="row.name">{{ row.name }}</span>
                      <a-tag
                        v-if="row.type === 'note' && row.contentFormat"
                        class="tree-fmt"
                        :color="row.contentFormat === 'markdown' ? 'blue' : 'green'"
                      >
                        {{ row.contentFormat === 'markdown' ? 'MD' : '富' }}
                      </a-tag>
                    </button>
                    <a-dropdown :trigger="['click']">
                      <button type="button" class="tree-more" @click.stop>
                        <MoreOutlined />
                      </button>
                      <template #overlay>
                        <a-menu>
                          <template v-if="row.type === 'folder'">
                            <a-menu-item @click="openCreateNoteInFolder(row.id)">
                              新建文档
                            </a-menu-item>
                            <a-menu-item @click="openCreateCategory(row.id)">
                              新建子文件夹
                            </a-menu-item>
                            <a-menu-item @click="openMoveDialog('folder', row)">
                              移动到…
                            </a-menu-item>
                            <a-menu-item
                              @click="openRenameCategory({ id: row.id, name: row.name })"
                            >
                              重命名
                            </a-menu-item>
                            <a-menu-item
                              danger
                              @click="confirmDeleteCategory({ id: row.id, name: row.name })"
                            >
                              删除
                            </a-menu-item>
                          </template>
                          <template v-else>
                            <a-menu-item @click="selectNote(row.id)">打开</a-menu-item>
                            <a-menu-item @click="openMoveDialog('note', row)">
                              移动到…
                            </a-menu-item>
                            <a-menu-item @click="moveNoteToRoot(row.id)">
                              移到未归档
                            </a-menu-item>
                            <a-menu-item danger @click="confirmDeleteNote(row)">
                              删除
                            </a-menu-item>
                          </template>
                        </a-menu>
                      </template>
                    </a-dropdown>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <div class="tree-meta muted">
            {{ treeMeta.folderCount }} 文件夹 · {{ treeMeta.noteCount }} 文档 · 虚拟滚动 · 可拖拽
          </div>
        </template>

        <!-- 仅目录树模式显示标签筛选入口 -->
        <template v-if="isExplorerTreeMode">
          <div class="side-head tags-head">
            <span class="side-title">标签筛选</span>
            <a-button type="text" size="small" class="icon-action" @click="openCreateTag">
              <PlusOutlined />
            </a-button>
          </div>
          <div class="tag-list">
            <button
              v-for="tag in tags"
              :key="tag.id"
              type="button"
              class="tag-chip"
              :class="{ active: filterTagId === tag.id }"
              @click="toggleTagFilter(tag.id)"
            >
              <span>#{{ tag.name }}</span>
              <span class="tag-count">{{ tag.noteCount ?? 0 }}</span>
            </button>
            <div v-if="!tags.length" class="side-empty">暂无标签</div>
          </div>
        </template>
      </div>

      <div class="trash-footer">
        <button
          type="button"
          class="trash-entry"
          :class="{ active: trashMode }"
          @click="toggleTrashMode"
        >
          <DeleteOutlined />
          <span>回收站</span>
          <span v-if="trashCount > 0" class="trash-badge">{{ trashCount > 99 ? '99+' : trashCount }}</span>
        </button>
      </div>
    </aside>

    <!-- 右：编辑 / 夹内概览 -->
    <section class="kb-editor-pane">
      <!-- 选中文件夹且未打开文档：夹内概览 -->
      <template v-if="!selectedId && !isCreating && !trashMode && !searchMode && !filterTagId && activeFolderId">
        <div class="folder-overview">
          <button type="button" class="mobile-back-btn folder-back" @click="closeMobileDetail">
            <ArrowLeftOutlined />
            <span>目录</span>
          </button>
          <div class="folder-ov-head">
            <FolderOpenOutlined class="folder-ov-icon" />
            <div class="folder-ov-text">
              <h2>{{ activeFolderName || '文件夹' }}</h2>
              <p class="muted">{{ folderChildNotes.length }} 篇文档 · {{ folderChildFolders.length }} 个子文件夹</p>
            </div>
            <div class="folder-ov-actions">
              <a-button type="primary" @click="openCreateNoteInFolder(activeFolderId)">在此新建文档</a-button>
              <a-button @click="openCreateCategory(activeFolderId)">新建子文件夹</a-button>
            </div>
          </div>
          <div v-if="!folderChildNotes.length && !folderChildFolders.length" class="folder-ov-empty">
            <EmptyState title="文件夹为空" description="可在此新建文档或子文件夹" />
          </div>
          <div v-else class="folder-ov-list">
            <button
              v-for="f in folderChildFolders"
              :key="'fov-f-' + f.id"
              type="button"
              class="folder-ov-item"
              @click="onTreeRowClick(f)"
            >
              <FolderOutlined />
              <span>{{ f.name }}</span>
              <span class="muted">文件夹</span>
            </button>
            <button
              v-for="n in folderChildNotes"
              :key="'fov-n-' + n.id"
              type="button"
              class="folder-ov-item"
              @click="selectNote(n.id)"
            >
              <FileTextOutlined />
              <span>{{ n.name }}</span>
              <span v-if="n.updatedAt" class="muted">{{ formatListTime(n.updatedAt) }}</span>
            </button>
          </div>
        </div>
      </template>
      <!-- 回收站为空：主区域居中展示 -->
      <template
        v-else-if="
          trashMode &&
          !selectedId &&
          !isCreating &&
          !listLoading &&
          total === 0 &&
          !keyword.trim()
        "
      >
        <div class="editor-empty trash-empty-center">
          <DeleteOutlined class="editor-empty-icon trash-empty-icon" />
          <h2 class="trash-empty-title">回收站为空</h2>
          <p class="muted">删除的文档会出现在这里，可恢复或永久删除</p>
          <div class="empty-create-actions">
            <a-button type="primary" @click="toggleTrashMode">返回目录</a-button>
          </div>
        </div>
      </template>
      <!-- 回收站未选中 / 搜索无结果 -->
      <template v-else-if="trashMode && !selectedId && !isCreating">
        <div class="editor-empty">
          <DeleteOutlined class="editor-empty-icon trash-empty-icon" />
          <p v-if="keyword.trim() && !notes.length">未找到匹配的已删除文档</p>
          <p v-else>从左侧选择一条已删除文档</p>
          <div class="empty-create-actions">
            <a-button type="primary" ghost @click="toggleTrashMode">返回目录</a-button>
          </div>
        </div>
      </template>
      <template v-else-if="!selectedId && !isCreating">
        <div class="editor-empty">
          <BookOutlined class="editor-empty-icon" />
          <p>在左侧目录选择文档，或新建文件夹 / 文档</p>
          <div class="empty-create-actions">
            <a-button type="primary" @click="openCreateNoteWith('html')">富文本新建</a-button>
            <a-button @click="openCreateNoteWith('markdown')">Markdown 新建</a-button>
            <a-button @click="openCreateCategory()">新建文件夹</a-button>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="editor-toolbar">
          <div class="toolbar-left">
            <button type="button" class="mobile-back-btn" @click="closeMobileDetail">
              <ArrowLeftOutlined />
              <span>目录</span>
            </button>
            <span class="doc-title-hint muted" :title="editTitle">{{ editTitle || '未命名笔记' }}</span>
            <a-tag :color="editFormat === 'markdown' ? 'blue' : 'green'" class="format-badge">
              <span class="fmt-full">{{ editFormat === 'markdown' ? 'Markdown' : '富文本' }}</span>
              <span class="fmt-short">{{ editFormat === 'markdown' ? 'MD' : '富' }}</span>
            </a-tag>
          </div>
          <div class="editor-actions">
            <a-tooltip :title="editPinned ? '取消置顶' : '置顶'">
              <a-button
                type="text"
                class="desktop-only-action"
                :disabled="editDeleted"
                @click="togglePin"
              >
                <PushpinOutlined :class="{ 'pin-on': editPinned }" />
              </a-button>
            </a-tooltip>
            <template v-if="editDeleted || trashMode">
              <a-button type="primary" ghost :loading="saving" @click="restoreCurrent">
                恢复
              </a-button>
              <a-popconfirm
                title="永久删除？将删除笔记、附件及 R2/本地存储对象，不可恢复。"
                ok-text="永久删除"
                ok-type="danger"
                cancel-text="取消"
                @confirm="permanentDeleteCurrent"
              >
                <a-button danger :loading="saving">永久删除</a-button>
              </a-popconfirm>
            </template>
            <template v-else>
              <a-button
                v-if="selectedId && !isCreating"
                class="desktop-only-action"
                @click="shareOpen = true"
              >
                分享
              </a-button>
              <a-dropdown
                v-if="selectedId && !isCreating"
                class="desktop-only-action"
                :trigger="['click']"
              >
                <a-button>更多</a-button>
                <template #overlay>
                  <a-menu @click="onDocMoreMenu">
                    <a-menu-item key="export">导出 Markdown</a-menu-item>
                    <a-menu-item key="publish-blog">
                      {{ editHaloPermalink ? '更新到博客' : '发布到博客' }}
                    </a-menu-item>
                    <a-menu-item key="duplicate">复制文档</a-menu-item>
                    <a-menu-item key="revisions">版本历史</a-menu-item>
                    <a-menu-item key="convert">
                      转为{{ editFormat === 'html' ? 'Markdown' : '富文本' }}
                    </a-menu-item>
                  </a-menu>
                </template>
              </a-dropdown>
              <a-button class="desktop-only-action" @click="confirmConvertFormat">
                转为{{ editFormat === 'html' ? 'Markdown' : '富文本' }}
              </a-button>
              <a-button type="primary" :loading="saving" @click="saveNote(false)">保存</a-button>
              <a-button
                v-if="selectedId"
                danger
                class="desktop-only-action"
                @click="confirmDeleteNote({ id: selectedId, name: editTitle || '未命名笔记' })"
              >
                删除
              </a-button>
              <!-- 小屏：次要操作收进「更多」 -->
              <span v-if="!editDeleted && !trashMode" class="mobile-more-actions">
                <a-dropdown :trigger="['click']" placement="bottomRight">
                  <a-button class="mobile-more-btn">
                    更多
                    <MoreOutlined />
                  </a-button>
                  <template #overlay>
                    <a-menu @click="onMobileMoreMenu">
                      <a-menu-item key="pin" :disabled="editDeleted">
                        {{ editPinned ? '取消置顶' : '置顶' }}
                      </a-menu-item>
                      <a-menu-item
                        v-if="selectedId && !isCreating"
                        key="share"
                      >
                        分享
                      </a-menu-item>
                      <a-menu-item key="export">导出 Markdown</a-menu-item>
                      <a-menu-item key="publish-blog">
                        {{ editHaloPermalink ? '更新到博客' : '发布到博客' }}
                      </a-menu-item>
                      <a-menu-item key="duplicate">复制文档</a-menu-item>
                      <a-menu-item key="revisions">版本历史</a-menu-item>
                      <a-menu-item key="convert">
                        转为{{ editFormat === 'html' ? 'Markdown' : '富文本' }}
                      </a-menu-item>
                      <a-menu-item
                        v-if="selectedId"
                        key="delete"
                        danger
                      >
                        删除
                      </a-menu-item>
                    </a-menu>
                  </template>
                </a-dropdown>
              </span>
            </template>
          </div>
        </div>
        <div class="editor-meta-row">
          <div class="doc-location" :title="folderPathText">
            <FolderOutlined class="loc-icon" />
            <span class="loc-text">{{ folderPathText }}</span>
            <span class="loc-hint muted">目录树中拖动可移动</span>
          </div>
          <div class="doc-tags" :class="{ disabled: editDeleted }">
            <button
              v-for="t in editTagChips"
              :key="t.id"
              type="button"
              class="doc-tag-chip"
              :disabled="editDeleted"
              :title="'移除 #' + t.name"
              @click="removeDocTag(t.id)"
            >
              <span>#{{ t.name }}</span>
              <span v-if="!editDeleted" class="chip-x">×</span>
            </button>
            <a-popover
              v-model:open="tagPickerOpen"
              trigger="click"
              placement="bottomLeft"
              :overlay-style="{ width: 'min(260px, 86vw)' }"
              destroy-tooltip-on-hide
            >
              <template #content>
                <div class="tag-picker">
                  <a-input
                    v-model:value="tagPickerQuery"
                    size="small"
                    allow-clear
                    placeholder="搜索或输入新建标签"
                    @press-enter="createAndApplyTag"
                  />
                  <div class="tag-picker-list">
                    <button
                      v-for="tag in filteredPickerTags"
                      :key="tag.id"
                      type="button"
                      class="tag-picker-item"
                      :class="{ on: editTagIds.includes(tag.id) }"
                      @click="toggleDocTag(tag.id)"
                    >
                      <span>#{{ tag.name }}</span>
                      <span v-if="editTagIds.includes(tag.id)" class="check">✓</span>
                    </button>
                    <div v-if="!filteredPickerTags.length" class="tag-picker-empty muted">
                      {{ tagPickerQuery.trim() ? '回车创建新标签' : '暂无标签' }}
                    </div>
                  </div>
                  <a-button
                    v-if="canCreatePickerTag"
                    type="link"
                    size="small"
                    block
                    class="tag-picker-create"
                    @click="createAndApplyTag"
                  >
                    创建并添加 #{{ tagPickerQuery.trim() }}
                  </a-button>
                </div>
              </template>
              <button
                type="button"
                class="add-tag-btn"
                :disabled="editDeleted"
              >
                + 标签
              </button>
            </a-popover>
          </div>
          <a-radio-group
            v-if="editFormat === 'markdown'"
            v-model:value="viewMode"
            size="small"
            button-style="solid"
            class="view-mode-group"
          >
            <a-radio-button value="edit">编辑</a-radio-button>
            <a-radio-button value="split" class="desktop-split-mode">分栏</a-radio-button>
            <a-radio-button value="preview">预览</a-radio-button>
          </a-radio-group>
        </div>
        <div
          class="editor-body"
          :class="editFormat === 'html' ? 'mode-html' : `mode-${viewMode}`"
        >
          <!-- 富文本：先快速预览壳，再挂编辑器，避免 setHtml 卡死首帧 -->
          <div v-if="editFormat === 'html'" class="html-stack">
            <div
              v-show="htmlShellVisible"
              class="html-fast-shell"
              :class="{ alone: !richEditorActive }"
            >
              <div v-if="contentLoading" class="shell-loading">加载正文中…</div>
              <div
                v-else
                class="shell-body"
                v-html="fastShellHtml || '<p class=&quot;muted&quot;>（无正文）</p>'"
              />
              <div v-if="richEditorActive && !editDeleted" class="shell-tip muted">
                编辑器准备中…
              </div>
            </div>
            <!-- 回收站只读：不挂重型编辑器 -->
            <RichEditor
              v-if="richEditorActive && !editDeleted"
              ref="richEditorRef"
              v-model="editContent"
              :note-id="selectedId"
              :disabled="false"
              @update:model-value="onContentTyped"
              @uploaded="onPendingFile"
              @ready="onRichEditorReady"
            />
          </div>
          <template v-else>
            <div
              v-if="viewMode !== 'preview'"
              class="md-doc-edit"
              :class="{ 'md-drag-over': mdDragOver }"
              @dragenter.prevent="onMdDragEnter"
              @dragover.prevent="onMdDragOver"
              @dragleave.prevent="onMdDragLeave"
              @drop.prevent="onMdDrop"
            >
              <input
                v-model="mdTitleLine"
                class="md-doc-title"
                type="text"
                maxlength="200"
                placeholder="标题"
                :disabled="editDeleted"
                @input="onMarkdownTitleInput"
                @blur="onMarkdownBlur"
              />
              <div class="doc-title-rule" aria-hidden="true" />
              <div v-if="!editDeleted" class="md-toolbar">
                <input
                  ref="mdImageInputRef"
                  type="file"
                  accept="image/*"
                  multiple
                  class="md-file-input"
                  @change="onMdImageInputChange"
                />
                <a-button
                  size="small"
                  type="primary"
                  ghost
                  :loading="mdImageUploading"
                  @click="triggerMdImagePick"
                >
                  插入图片
                </a-button>
                <span class="md-toolbar-tip muted">
                  支持上传 / 粘贴 / 拖入；在预览区点击图片可缩放
                </span>
              </div>
              <a-textarea
                ref="mdBodyAreaRef"
                v-model:value="mdBodyText"
                class="md-input md-body-input"
                placeholder="正文从这里开始… 可粘贴或拖入图片"
                :disabled="editDeleted"
                :auto-size="false"
                @blur="onMarkdownBlur"
                @input="onMarkdownBodyInput"
                @paste="onMdPaste"
              />
            </div>
            <div
              v-if="viewMode !== 'edit'"
              class="md-preview doc-preview"
              @click="onMarkdownPreviewClick"
              v-html="previewHtml"
            />
            <div
              v-if="mdImgMenu.visible"
              class="md-img-menu"
              :style="{ left: mdImgMenu.x + 'px', top: mdImgMenu.y + 'px' }"
              @mousedown.prevent
            >
              <span class="md-img-menu-label">图片宽度</span>
              <button type="button" @click="applyMdImageWidth(30)">30%</button>
              <button type="button" @click="applyMdImageWidth(50)">50%</button>
              <button type="button" @click="applyMdImageWidth(75)">75%</button>
              <button type="button" @click="applyMdImageWidth(100)">100%</button>
              <button type="button" class="ghost" @click="closeMdImgMenu">关闭</button>
            </div>
          </template>
        </div>
        <!-- 附件面板略延迟，不与正文首屏抢主线程 -->
        <FilePanel
          v-if="filePanelActive"
          ref="filePanelRef"
          :note-id="selectedId"
          :disabled="editDeleted"
          @pending-uploaded="onPendingFile"
        />
        <div class="editor-status">
          <span v-if="saveHint" :class="{ 'save-ok': saveHint === '已保存', 'save-warn': saveHint === '未保存' || saveHint.startsWith('保存失败') }">
            {{ saveHint }}
            <template v-if="saveHint === '未保存'"> · Ctrl+S 保存</template>
          </span>
          <span v-else-if="editUpdatedAt">更新于 {{ formatTime(editUpdatedAt) }}</span>
          <span v-if="outlineHeadings.length" class="outline-inline muted">
            大纲 {{ outlineHeadings.length }} 节
          </span>
        </div>
        <div v-if="outlineHeadings.length && !editDeleted" class="doc-outline">
          <div class="outline-title">大纲</div>
          <button
            v-for="(h, i) in outlineHeadings"
            :key="i"
            type="button"
            class="outline-item"
            :style="{ paddingLeft: `${8 + (h.level - 1) * 12}px` }"
            :title="h.text"
            @click="scrollToOutline(h)"
          >
            {{ h.text }}
          </button>
        </div>
      </template>
    </section>

    <a-modal
      v-model:open="revisionModalOpen"
      title="版本历史"
      :footer="null"
      width="560px"
      destroy-on-close
    >
      <div v-if="revisionLoading" class="muted">加载中…</div>
      <div v-else-if="!revisions.length" class="muted">暂无历史版本（保存正文变更后会出现）</div>
      <ul v-else class="rev-list">
        <li v-for="r in revisions" :key="r.id" class="rev-item">
          <div class="rev-meta">
            <strong>{{ r.title || '未命名' }}</strong>
            <span class="muted">{{ formatTimeFull(r.createdAt) }} · {{ r.source || 'save' }}</span>
          </div>
          <p v-if="r.snippet" class="rev-snip muted">{{ r.snippet }}</p>
          <a-button
            size="small"
            type="primary"
            ghost
            :loading="revisionRestoring === r.id"
            @click="restoreRevision(r.id)"
          >
            恢复此版本
          </a-button>
        </li>
      </ul>
    </a-modal>

    <!-- 删除文件夹策略（横向卡片，不用 radio 竖排） -->
    <a-modal
      v-model:open="deleteFolderOpen"
      :title="deleteFolderTarget ? `删除文件夹「${deleteFolderTarget.name}」` : '删除文件夹'"
      ok-text="确认删除"
      ok-type="danger"
      cancel-text="取消"
      :width="680"
      wrap-class-name="kb-delete-folder-modal"
      :confirm-loading="deleteFolderLoading"
      @ok="submitDeleteFolder"
    >
      <p class="delete-mode-tip muted">请选择处理方式（横向选择一项）：</p>
      <div class="delete-mode-row" role="radiogroup" aria-label="删除策略">
        <button
          v-for="opt in deleteFolderModeOptions"
          :key="opt.value"
          type="button"
          class="delete-mode-card"
          :class="{ active: deleteFolderMode === opt.value }"
          role="radio"
          :aria-checked="deleteFolderMode === opt.value"
          @click="deleteFolderMode = opt.value"
        >
          <span class="dm-title">{{ opt.title }}</span>
          <span class="dm-desc muted">{{ opt.desc }}</span>
        </button>
      </div>
    </a-modal>

    <!-- 移动到文件夹 -->
    <a-modal
      v-model:open="moveModalOpen"
      :title="moveModalTitle"
      ok-text="移动到此处"
      cancel-text="取消"
      :confirm-loading="moveModalLoading"
      @ok="submitMoveModal"
    >
      <p class="move-tip muted">选择目标文件夹（或不选=根目录 / 未归档）</p>
      <div class="move-folder-list">
        <button
          type="button"
          class="move-folder-item"
          :class="{ active: moveTargetId === null }"
          @click="moveTargetId = null"
        >
          <InboxOutlined />
          根目录 / 未归档
        </button>
        <button
          v-for="f in flatCategories"
          :key="f.id"
          type="button"
          class="move-folder-item"
          :class="{ active: moveTargetId === f.id, disabled: isInvalidMoveTarget(f.id) }"
          :style="{ paddingLeft: `${12 + f.depth * 14}px` }"
          :disabled="isInvalidMoveTarget(f.id)"
          @click="moveTargetId = f.id"
        >
          <FolderOutlined />
          {{ f.name }}
        </button>
      </div>
    </a-modal>

    <!-- 新建文件夹 -->
    <a-modal
      v-model:open="catModalOpen"
      :title="catModalParentId ? '新建子文件夹' : '新建文件夹'"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="catModalLoading"
      @ok="submitCategory"
    >
      <a-input v-model:value="catModalName" placeholder="文件夹名称" allow-clear @press-enter="submitCategory" />
    </a-modal>

    <!-- 重命名文件夹 -->
    <a-modal
      v-model:open="renameModalOpen"
      title="重命名文件夹"
      ok-text="保存"
      cancel-text="取消"
      :confirm-loading="renameModalLoading"
      @ok="submitRenameCategory"
    >
      <a-input v-model:value="renameModalName" allow-clear @press-enter="submitRenameCategory" />
    </a-modal>

    <!-- 新建标签 -->
    <a-modal
      v-model:open="tagModalOpen"
      title="新建标签"
      ok-text="创建"
      cancel-text="取消"
      :confirm-loading="tagModalLoading"
      @ok="submitTag"
    >
      <a-input v-model:value="tagModalName" placeholder="标签名称" allow-clear @press-enter="submitTag" />
    </a-modal>

    <ShareModal v-model:open="shareOpen" :note-id="selectedId" />

    <!-- 新建：先选格式 -->
    <a-modal
      v-model:open="createFormatOpen"
      title="新建笔记"
      :footer="null"
      width="480px"
      destroy-on-close
    >
      <p class="create-format-tip">请先选择编辑格式，选定后编辑过程中格式固定；如需更换可用工具栏「转为…」并转换内容。</p>
      <div class="create-format-cards">
        <button type="button" class="format-card" @click="openCreateNoteWith('html')">
          <div class="format-card-title">富文本</div>
          <div class="format-card-desc">适合大多数人：所见即所得，可插入图片/视频、排版简单直观</div>
          <div class="format-card-tag recommended">推荐</div>
        </button>
        <button type="button" class="format-card" @click="openCreateNoteWith('markdown')">
          <div class="format-card-title">Markdown</div>
          <div class="format-card-desc">适合熟悉 MD 语法的人：纯文本书写，适合技术笔记与代码块</div>
        </button>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch
} from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  BookOutlined,
  CaretDownOutlined,
  CaretRightOutlined,
  DeleteOutlined,
  FileTextOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MoreOutlined,
  PlusOutlined,
  PushpinOutlined
} from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import EmptyState from '@/components/EmptyState.vue'
import ShareModal from './ShareModal.vue'
import {
  kbApi,
  injectKbMediaTokens,
  mdImageSyntax,
  stripKbMediaTokens,
  stripKbMediaTokensAll,
  type KbCategory,
  type KbContentFormat,
  type KbExplorerNode,
  type KbNoteItem,
  type KbNoteRevision,
  type KbTag
} from '@/api/kb.api'
import {
  emptyHtmlDoc,
  emptyMarkdownDoc,
  ensureHtmlHasTitle,
  ensureMarkdownHasTitle,
  extractTitle,
  isBlankDraftContent,
  joinMarkdownDoc,
  splitMarkdownDoc
} from './kbDocTitle'
import {
  appendMarkdownImage,
  imageSrcKey,
  setMarkdownImageWidth
} from './kbMdImage'
import {
  TREE_ROW_HEIGHT,
  applyDragGhost,
  calcVirtualRange,
  cleanupDragGhost,
  scrollIndexIntoView
} from './kbTreeVirtual'
import { sanitizeHtml } from '@/utils/sanitizeHtml'

/** 异步加载，避免 turndown/wangeditor/docx 阻塞首屏路由 */
const RichEditor = defineAsyncComponent(() => import('./RichEditor.vue'))
const FilePanel = defineAsyncComponent(() => import('./FilePanel.vue'))

/** 轻量 MD 渲染（仅预览用）；按需加载 markdown-it */
let mdRender: ((src: string) => string) | null = null
async function ensureMd() {
  if (mdRender) return mdRender
  const mod = await import('markdown-it')
  const multimd = await import('markdown-it-multimd-table')
  const MarkdownIt = mod.default
  const tablePlugin = (multimd as any).default || multimd
  // html:true — 允许 <img style="width:.."> 做缩放；渲染后 sanitize
  const md = new MarkdownIt({ html: true, linkify: true, breaks: true }).use(tablePlugin, {
    multiline: true,
    rowspan: true,
    headerless: true
  })
  mdRender = (src: string) => {
    const raw = src || ''
    let html = md.render(raw)
    html = injectKbMediaTokens(html)
    html = html.replace(/<img\b/gi, '<img class="kb-md-img" title="点击调整大小"')
    return sanitizeHtml(html)
  }
  return mdRender
}

/** 简易 HTML → 纯文本/近似 Markdown，避免 turndown 依赖预构建失败拖垮整页 */
function htmlToMarkdownLite(html: string): string {
  if (!html) return ''
  let s = html
  s = s.replace(/<br\s*\/?>/gi, '\n')
  s = s.replace(/<\/p>/gi, '\n\n')
  s = s.replace(/<\/div>/gi, '\n')
  s = s.replace(/<\/h([1-6])>/gi, '\n\n')
  s = s.replace(/<h([1-6])[^>]*>/gi, (_, n) => '#'.repeat(Number(n)) + ' ')
  s = s.replace(/<li[^>]*>/gi, '- ')
  s = s.replace(/<\/li>/gi, '\n')
  s = s.replace(/<strong[^>]*>([\s\S]*?)<\/strong>/gi, '**$1**')
  s = s.replace(/<b[^>]*>([\s\S]*?)<\/b>/gi, '**$1**')
  s = s.replace(/<em[^>]*>([\s\S]*?)<\/em>/gi, '*$1*')
  s = s.replace(/<code[^>]*>([\s\S]*?)<\/code>/gi, '`$1`')
  s = s.replace(/<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi, '[$2]($1)')
  // 暂存可缩放 HTML 图片，避免被剥标签时丢掉 width
  const imgs: string[] = []
  s = s.replace(/<img\b[^>]*\/?\s*>/gi, (full) => {
    imgs.push(full)
    return `\n\n@@KBIMG${imgs.length - 1}@@\n\n`
  })
  s = s.replace(/<[^>]+>/g, '')
  s = s
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .replace(/&quot;/g, '"')
  s = s.replace(/@@KBIMG(\d+)@@/g, (_, i) => imgs[Number(i)] || '')
  return s.replace(/\n{3,}/g, '\n\n').trim()
}

const categories = ref<KbCategory[]>([])
const tags = ref<KbTag[]>([])
const notes = ref<KbNoteItem[]>([])
const total = ref(0)
const page = ref(0)
const pageSize = 50
const listLoading = ref(false)
const treeLoading = ref(false)
const keyword = ref('')
/** 搜索模式：侧栏显示搜索结果而非目录树 */
const searchMode = ref(false)
const filterCategoryId = ref<string | null>(null)
const filterTagId = ref<string | null>(null)
/** 当前选中的文件夹（夹内概览 / 新建默认落点） */
const activeFolderId = ref<string | null>(null)
const treeRoots = ref<KbExplorerNode[]>([])
const treeMeta = ref({ folderCount: 0, noteCount: 0 })
const expandedIds = ref<Set<string>>(new Set())
/** 拖拽中的节点 key/type/id */
const dragPayload = ref<{
  key: string
  type: 'folder' | 'note'
  id: string
  parentId?: string | null
  name: string
} | null>(null)
const dropTargetKey = ref<string | null>(null)
/** 移动对话框 */
const moveModalOpen = ref(false)
const moveModalLoading = ref(false)
const moveModalType = ref<'folder' | 'note'>('note')
const moveModalItemId = ref<string | null>(null)
const moveModalItemName = ref('')
const moveTargetId = ref<string | null>(null)
/** 左侧回收站模式：只看软删笔记 */
const trashMode = ref(false)
const trashCount = ref(0)
const emptying = ref(false)
/** 最近 / 置顶快捷列表 */
const listViewMode = ref<'recent' | 'pinned' | null>(null)

/** 是否为目录树主视图（非回收站/搜索/标签/快捷列表） */
const isExplorerTreeMode = computed(
  () =>
    !trashMode.value &&
    !searchMode.value &&
    !filterTagId.value &&
    !listViewMode.value
)

const revisionModalOpen = ref(false)
const revisionLoading = ref(false)
const revisions = ref<KbNoteRevision[]>([])
const revisionRestoring = ref<string | null>(null)

/** 防抖自动保存 */
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null
const AUTO_SAVE_MS = 1800

type OutlineHeading = { level: number; text: string; anchor: string }
const outlineHeadings = computed(() => buildOutline(editContent.value, editFormat.value))

const selectedId = ref<string | null>(null)
const isCreating = ref(false)

/**
 * 小屏：列表 ↔ 详情全屏切换（桌面始终双栏，此 class 仅 CSS 消费）
 * 打开文档 / 新建 / 夹内概览 时进入详情
 */
const mobileDetailOpen = computed(() => {
  if (selectedId.value || isCreating.value) return true
  if (
    !trashMode.value &&
    !searchMode.value &&
    !filterTagId.value &&
    activeFolderId.value
  ) {
    return true
  }
  return false
})

/** 小屏返回目录：收起详情，保留搜索/回收站/标签筛选态 */
async function closeMobileDetail() {
  if (dirty.value && !editDeleted.value && selectedId.value && !isCreating.value) {
    try {
      await saveNote(true)
    } catch {
      /* keep going */
    }
  } else if (isCreating.value || (!selectedId.value && dirty.value)) {
    discardBlankDraftIfNeeded()
  }
  selectedId.value = null
  isCreating.value = false
  activeFolderId.value = null
  richEditorActive.value = false
  htmlShellVisible.value = false
  filePanelActive.value = false
}

function onMobileMoreMenu({ key }: { key: string }) {
  if (key === 'pin') {
    void togglePin()
    return
  }
  if (key === 'share') {
    shareOpen.value = true
    return
  }
  if (key === 'export' || key === 'duplicate' || key === 'revisions' || key === 'convert' || key === 'publish-blog') {
    onDocMoreMenu({ key })
    return
  }
  if (key === 'delete' && selectedId.value) {
    confirmDeleteNote({ id: selectedId.value, name: editTitle.value || '未命名笔记' })
  }
}

function onDocMoreMenu({ key }: { key: string }) {
  if (key === 'export') {
    void exportCurrentNote()
    return
  }
  if (key === 'publish-blog') {
    void publishCurrentNoteToBlog()
    return
  }
  if (key === 'duplicate') {
    void duplicateCurrentNote()
    return
  }
  if (key === 'revisions') {
    void openRevisions()
    return
  }
  if (key === 'convert') {
    confirmConvertFormat()
  }
}
const editTitle = ref('')
const editHaloPermalink = ref('')
const publishingBlog = ref(false)
const editContent = ref('')
/** Markdown 编辑区：标题与正文视觉分离（仍合并进 editContent 存库） */
const mdTitleLine = ref('未命名笔记')
const mdBodyText = ref('')
const mdImgMenu = ref<{ visible: boolean; x: number; y: number; src: string }>({
  visible: false,
  x: 0,
  y: 0,
  src: ''
})
const editFormat = ref<KbContentFormat>('html')
const editCategoryId = ref<string | undefined>(undefined)
const editTagIds = ref<string[]>([])
const editPinned = ref(false)
const editDeleted = ref(false)
const editUpdatedAt = ref<string | undefined>()
const saving = ref(false)
const saveHint = ref('')
const viewMode = ref<'edit' | 'split' | 'preview'>('split')
const dirty = ref(false)
const applying = ref(false)

const catModalOpen = ref(false)
const catModalName = ref('')
const catModalParentId = ref<string | null>(null)
const catModalLoading = ref(false)

const renameModalOpen = ref(false)
const renameModalName = ref('')
const renameModalId = ref<string | null>(null)
const renameModalLoading = ref(false)

const tagModalOpen = ref(false)
const tagModalName = ref('')
const tagModalLoading = ref(false)

/** 新建时先选格式 */
const createFormatOpen = ref(false)
const shareOpen = ref(false)
/** 未关联笔记的附件 id（拖入时 note 尚未保存） */
const pendingFileIds = ref<string[]>([])
const filePanelRef = ref<{ reload: () => Promise<void> } | null>(null)
const richEditorRef = ref<{ flushEmit?: () => void } | null>(null)

/** 打开笔记：快速 HTML 壳 → 再挂富文本编辑器 */
const contentLoading = ref(false)
const htmlShellVisible = ref(false)
const richEditorActive = ref(false)
const filePanelActive = ref(false)
const fastShellHtml = ref('')
let editorBootTimer: ReturnType<typeof setTimeout> | null = null
let filePanelTimer: ReturnType<typeof setTimeout> | null = null
let openSeq = 0

/** Markdown 预览防抖 */
let mdPreviewTimer: ReturnType<typeof setTimeout> | null = null

function clearBootTimers() {
  if (editorBootTimer) {
    clearTimeout(editorBootTimer)
    editorBootTimer = null
  }
  if (filePanelTimer) {
    clearTimeout(filePanelTimer)
    filePanelTimer = null
  }
}

/** 先画快速预览，空闲后再挂 WangEditor（避免首帧卡死） */
function scheduleRichEditorBoot(seq: number) {
  richEditorActive.value = false
  htmlShellVisible.value = true
  if (editorBootTimer) clearTimeout(editorBootTimer)

  const boot = () => {
    if (seq !== openSeq) return
    // 只读（回收站）永不挂重型编辑器
    if (editDeleted.value) {
      richEditorActive.value = false
      htmlShellVisible.value = true
      return
    }
    richEditorActive.value = true
  }

  const ric = (window as any).requestIdleCallback as
    | undefined
    | ((cb: () => void, opts?: { timeout: number }) => number)
  if (typeof ric === 'function') {
    ric(boot, { timeout: 180 })
  } else {
    editorBootTimer = setTimeout(boot, 48)
  }

  // 附件再晚一点
  if (filePanelTimer) clearTimeout(filePanelTimer)
  filePanelTimer = setTimeout(() => {
    if (seq !== openSeq) return
    filePanelActive.value = true
  }, 120)
}

function onRichEditorReady() {
  // 编辑器灌入完成，撤掉快速壳
  htmlShellVisible.value = false
  if (saveHint.value === '加载正文中…' || saveHint.value === '编辑器准备中…') {
    saveHint.value = ''
  }
}

function updateFastShell(content: string, format: KbContentFormat) {
  if (format === 'html') {
    fastShellHtml.value = sanitizeHtml(injectKbMediaTokens(content || ''))
  } else {
    fastShellHtml.value = ''
  }
}

function onPendingFile(fileId: string) {
  if (!pendingFileIds.value.includes(fileId)) {
    pendingFileIds.value.push(fileId)
  }
}

async function bindPendingFiles(noteId: string) {
  if (!pendingFileIds.value.length) return
  const ids = [...pendingFileIds.value]
  pendingFileIds.value = []
  for (const fid of ids) {
    try {
      await kbApi.bindFile(fid, noteId)
    } catch {
      /* 忽略单条失败 */
    }
  }
  await filePanelRef.value?.reload?.()
}

interface FlatCat {
  id: string
  name: string
  depth: number
}

interface FlatTreeRow {
  key: string
  type: 'folder' | 'note'
  id: string
  name: string
  depth: number
  pinned?: boolean
  contentFormat?: string
  updatedAt?: string
  parentId?: string | null
  /** 原始子节点（仅 folder 且用于夹内概览） */
  children?: KbExplorerNode[]
}

const flatCategories = computed(() => {
  const out: FlatCat[] = []
  const walk = (nodes: KbCategory[], depth: number) => {
    for (const n of nodes) {
      out.push({ id: n.id, name: n.name, depth })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

/** 当前文档所在文件夹路径（只读展示，移动请拖目录树） */
const folderPathText = computed(() => {
  const id = editCategoryId.value
  if (!id) return '未归档'
  const map = new Map(flatCategories.value.map((c) => [c.id, c]))
  // 需要 parent 链：从 categories 树建 parentMap
  const parentMap = new Map<string, string | null>()
  const nameMap = new Map<string, string>()
  const walk = (nodes: KbCategory[], parent: string | null) => {
    for (const n of nodes) {
      parentMap.set(n.id, parent)
      nameMap.set(n.id, n.name)
      if (n.children?.length) walk(n.children, n.id)
    }
  }
  walk(categories.value, null)
  const parts: string[] = []
  let cur: string | null = id
  let guard = 0
  while (cur && guard++ < 32) {
    parts.unshift(nameMap.get(cur) || map.get(cur)?.name || '…')
    cur = parentMap.get(cur) ?? null
  }
  return parts.length ? parts.join(' / ') : '未归档'
})

const editTagChips = computed(() => {
  const set = new Set(editTagIds.value)
  return tags.value.filter((t) => set.has(t.id))
})

const tagPickerOpen = ref(false)
const tagPickerQuery = ref('')

const filteredPickerTags = computed(() => {
  const q = tagPickerQuery.value.trim().toLowerCase()
  if (!q) return tags.value
  return tags.value.filter((t) => t.name.toLowerCase().includes(q))
})

const canCreatePickerTag = computed(() => {
  const name = tagPickerQuery.value.trim()
  if (!name) return false
  return !tags.value.some((t) => t.name.toLowerCase() === name.toLowerCase())
})

/** 展开后的目录树行（文件夹 + 文档） */
const flatTreeRows = computed(() => {
  const out: FlatTreeRow[] = []
  const walk = (nodes: KbExplorerNode[], depth: number) => {
    for (const n of nodes) {
      const type = n.type === 'folder' ? 'folder' : 'note'
      out.push({
        key: `${type}-${n.id}`,
        type,
        id: n.id,
        name: n.name || (type === 'folder' ? '未命名文件夹' : '未命名笔记'),
        depth,
        pinned: !!n.pinned,
        contentFormat: n.contentFormat,
        updatedAt: n.updatedAt,
        parentId: n.parentId,
        children: n.children
      })
      if (type === 'folder' && expandedIds.value.has(n.id) && n.children?.length) {
        walk(n.children, depth + 1)
      }
    }
  }
  walk(treeRoots.value, 0)
  return out
})

/** —— 虚拟滚动 —— */
const treeViewportRef = ref<HTMLElement | null>(null)
const treeScrollTop = ref(0)
const treeViewportH = ref(360)
let treeResizeObs: ResizeObserver | null = null
let dragGhostCleanup: (() => void) | null = null

const treeVirtual = computed(() =>
  calcVirtualRange(treeScrollTop.value, treeViewportH.value, flatTreeRows.value.length)
)

const virtualTreeRows = computed(() => {
  const { start, end } = treeVirtual.value
  return flatTreeRows.value.slice(start, end)
})

function onTreeScroll(e: Event) {
  const el = e.target as HTMLElement
  treeScrollTop.value = el.scrollTop
}

function measureTreeViewport() {
  const el = treeViewportRef.value
  if (!el) return
  treeViewportH.value = el.clientHeight || 360
}

function scrollTreeToNote(noteId: string) {
  const idx = flatTreeRows.value.findIndex((r) => r.type === 'note' && r.id === noteId)
  if (idx < 0) return
  nextTick(() => {
    scrollIndexIntoView(treeViewportRef.value, idx)
    // 同步 scrollTop 状态，避免窗口偏移不同步
    if (treeViewportRef.value) {
      treeScrollTop.value = treeViewportRef.value.scrollTop
    }
  })
}

function bindTreeViewport() {
  unbindTreeViewport()
  nextTick(() => {
    measureTreeViewport()
    const el = treeViewportRef.value
    if (!el || typeof ResizeObserver === 'undefined') return
    treeResizeObs = new ResizeObserver(() => measureTreeViewport())
    treeResizeObs.observe(el)
  })
}

function unbindTreeViewport() {
  treeResizeObs?.disconnect()
  treeResizeObs = null
}

// 展开/树数据变化后重新测量；节点变少时校正 scrollTop
watch(
  () => flatTreeRows.value.length,
  () => {
    nextTick(() => {
      measureTreeViewport()
      const el = treeViewportRef.value
      if (!el) return
      const maxScroll = Math.max(0, treeVirtual.value.totalHeight - el.clientHeight)
      if (el.scrollTop > maxScroll) {
        el.scrollTop = maxScroll
        treeScrollTop.value = maxScroll
      }
    })
  }
)

const activeFolderName = computed(() => {
  if (!activeFolderId.value) return ''
  const row = flatTreeRows.value.find(
    (r) => r.type === 'folder' && r.id === activeFolderId.value
  )
  if (row) return row.name
  const cat = flatCategories.value.find((c) => c.id === activeFolderId.value)
  return cat?.name || ''
})

function findExplorerNode(
  nodes: KbExplorerNode[],
  id: string,
  type: 'folder' | 'note'
): KbExplorerNode | null {
  for (const n of nodes) {
    if (n.type === type && n.id === id) return n
    if (n.children?.length) {
      const hit = findExplorerNode(n.children, id, type)
      if (hit) return hit
    }
  }
  return null
}

const folderChildFolders = computed((): FlatTreeRow[] => {
  if (!activeFolderId.value) return []
  const node = findExplorerNode(treeRoots.value, activeFolderId.value, 'folder')
  if (!node?.children) return []
  return node.children
    .filter((c) => c.type === 'folder')
    .map((c) => ({
      key: `folder-${c.id}`,
      type: 'folder' as const,
      id: c.id,
      name: c.name,
      depth: 0,
      children: c.children
    }))
})

const folderChildNotes = computed((): FlatTreeRow[] => {
  if (!activeFolderId.value) return []
  const node = findExplorerNode(treeRoots.value, activeFolderId.value, 'folder')
  if (!node?.children) return []
  return node.children
    .filter((c) => c.type === 'note')
    .map((c) => ({
      key: `note-${c.id}`,
      type: 'note' as const,
      id: c.id,
      name: c.name,
      depth: 0,
      pinned: !!c.pinned,
      contentFormat: c.contentFormat,
      updatedAt: c.updatedAt
    }))
})

const previewHtml = ref('')
const mdBodyAreaRef = ref<any>(null)
const mdImageInputRef = ref<HTMLInputElement | null>(null)
const mdImageUploading = ref(false)
const mdDragOver = ref(false)
let mdDragDepth = 0

function getMdTextareaEl(): HTMLTextAreaElement | null {
  const comp = mdBodyAreaRef.value
  if (!comp) return null
  const root = comp.$el as HTMLElement | undefined
  if (!root) return null
  if (root.tagName === 'TEXTAREA') return root as HTMLTextAreaElement
  return root.querySelector?.('textarea') || null
}

/** 在光标处插入 Markdown 片段 */
function insertMarkdownAtCursor(snippet: string) {
  const ta = getMdTextareaEl()
  const text = mdBodyText.value || ''
  if (!ta) {
    const pad = text && !text.endsWith('\n') ? '\n\n' : text ? '\n' : ''
    mdBodyText.value = text + pad + snippet + '\n'
  } else {
    const start = ta.selectionStart ?? text.length
    const end = ta.selectionEnd ?? text.length
    const before = text.slice(0, start)
    const after = text.slice(end)
    const needNlBefore = before.length > 0 && !before.endsWith('\n')
    const insert = (needNlBefore ? '\n' : '') + snippet + '\n'
    mdBodyText.value = before + insert + after
    const pos = before.length + insert.length
    requestAnimationFrame(() => {
      ta.focus()
      ta.setSelectionRange(pos, pos)
    })
  }
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
}

async function uploadImagesForMarkdown(files: File[]) {
  const images = files.filter((f) => f.type.startsWith('image/'))
  if (!images.length) {
    message.warning('请选择图片文件')
    return
  }
  mdImageUploading.value = true
  try {
    for (const file of images) {
      const res = await kbApi.uploadFile(file, selectedId.value || undefined)
      onPendingFile(res.data.id)
      const alt = (file.name || 'image').replace(/\.[^.]+$/, '')
      const path = res.data.contentPath.startsWith('/')
        ? res.data.contentPath
        : `/${res.data.contentPath}`
      // 使用可缩放 HTML img（预览可点宽度）；存库干净路径
      insertMarkdownAtCursor(
        appendMarkdownImage('', path, alt, 100).trim()
      )
    }
    if (viewMode.value === 'edit') viewMode.value = 'split'
    message.success(
      images.length > 1
        ? `已插入 ${images.length} 张图片，预览中点击可缩放`
        : '已插入图片，预览中点击可缩放'
    )
  } catch (e: any) {
    message.error(e?.message || '图片上传失败')
  } finally {
    mdImageUploading.value = false
  }
}

function triggerMdImagePick() {
  mdImageInputRef.value?.click()
}

function onMdImageInputChange(e: Event) {
  const input = e.target as HTMLInputElement
  const list = input.files
  if (list?.length) {
    void uploadImagesForMarkdown(Array.from(list))
  }
  input.value = ''
}

function onMdPaste(e: ClipboardEvent) {
  if (editDeleted.value) return
  const items = e.clipboardData?.items
  if (!items?.length) return
  const files: File[] = []
  for (let i = 0; i < items.length; i++) {
    const it = items[i]
    if (it.kind === 'file' && it.type.startsWith('image/')) {
      const f = it.getAsFile()
      if (f) files.push(f)
    }
  }
  if (!files.length) return
  e.preventDefault()
  void uploadImagesForMarkdown(files)
}

function onMdDragEnter() {
  if (editDeleted.value) return
  mdDragDepth++
  mdDragOver.value = true
}

function onMdDragOver() {
  if (editDeleted.value) return
  mdDragOver.value = true
}

function onMdDragLeave() {
  mdDragDepth = Math.max(0, mdDragDepth - 1)
  if (mdDragDepth === 0) mdDragOver.value = false
}

function onMdDrop(e: DragEvent) {
  mdDragDepth = 0
  mdDragOver.value = false
  if (editDeleted.value) return
  const list = e.dataTransfer?.files
  if (!list?.length) return
  void uploadImagesForMarkdown(Array.from(list))
}

async function renderMarkdownPreview(src: string) {
  try {
    const render = await ensureMd()
    // ensureMd 内已 inject + sanitize；此处再补一次 token（幂等）
    previewHtml.value = injectKbMediaTokens(render(src || ''))
  } catch {
    previewHtml.value = `<pre>${(src || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')}</pre>`
  }
}

function scheduleMarkdownPreview() {
  if (editFormat.value !== 'markdown') return
  // 仅编辑模式不渲染预览，避免无意义全量 markdown-it
  if (viewMode.value === 'edit') return
  if (mdPreviewTimer) clearTimeout(mdPreviewTimer)
  mdPreviewTimer = setTimeout(() => {
    mdPreviewTimer = null
    void renderMarkdownPreview(editContent.value)
  }, 320)
}

watch(editFormat, (f) => {
  if (f === 'html') {
    previewHtml.value = ''
    return
  }
  scheduleMarkdownPreview()
})

watch(viewMode, () => {
  if (editFormat.value === 'markdown') scheduleMarkdownPreview()
})

function syncTitleFromContent() {
  editTitle.value = extractTitle(editContent.value || '', editFormat.value)
}

function syncMarkdownFromParts() {
  editContent.value = joinMarkdownDoc(mdTitleLine.value, mdBodyText.value)
  editTitle.value = (mdTitleLine.value || '').trim() || '未命名笔记'
}

function loadMarkdownParts(md: string) {
  const { title, body } = splitMarkdownDoc(md || '')
  mdTitleLine.value = title
  mdBodyText.value = body
  editContent.value = joinMarkdownDoc(title, body)
  editTitle.value = title
}

function onContentTyped() {
  if (applying.value) return
  dirty.value = true
  saveHint.value = '未保存'
  // 首行/H1 同步为列表标题
  syncTitleFromContent()
  scheduleDebouncedAutoSave()
}

function onMarkdownTitleInput() {
  if (applying.value) return
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
  scheduleDebouncedAutoSave()
}

function onMarkdownBodyInput() {
  if (applying.value) return
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  scheduleMarkdownPreview()
  scheduleDebouncedAutoSave()
}

function scheduleDebouncedAutoSave() {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null
    autoSave()
  }, AUTO_SAVE_MS)
}

function clearDebouncedAutoSave() {
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
    autoSaveTimer = null
  }
}

function buildOutline(content: string, format: KbContentFormat): OutlineHeading[] {
  if (!content) return []
  const items: OutlineHeading[] = []
  if (format === 'markdown') {
    const lines = content.split('\n')
    for (const line of lines) {
      const m = /^(#{1,3})\s+(.+?)\s*$/.exec(line)
      if (!m) continue
      const text = m[2].replace(/[#*`_[\]]/g, '').trim()
      if (!text) continue
      items.push({ level: m[1].length, text, anchor: text })
      if (items.length >= 40) break
    }
  } else {
    const re = /<h([1-3])[^>]*>([\s\S]*?)<\/h\1>/gi
    let m: RegExpExecArray | null
    while ((m = re.exec(content)) && items.length < 40) {
      const text = m[2].replace(/<[^>]+>/g, '').trim()
      if (!text) continue
      items.push({ level: Number(m[1]), text, anchor: text })
    }
  }
  return items
}

function scrollToOutline(h: OutlineHeading) {
  // Markdown 预览区 / 富文本：按标题文本粗定位
  const pane = document.querySelector('.kb-editor-pane .md-preview, .kb-editor-pane .w-e-text-container')
  if (!pane) return
  const nodes = pane.querySelectorAll('h1,h2,h3')
  for (const node of Array.from(nodes)) {
    if ((node.textContent || '').trim() === h.text) {
      node.scrollIntoView({ behavior: 'smooth', block: 'start' })
      return
    }
  }
}

function onMarkdownBlur() {
  syncMarkdownFromParts()
  if (mdPreviewTimer) {
    clearTimeout(mdPreviewTimer)
    mdPreviewTimer = null
    void renderMarkdownPreview(editContent.value)
  }
  autoSave()
}

function closeMdImgMenu() {
  mdImgMenu.value = { visible: false, x: 0, y: 0, src: '' }
}

function onMarkdownPreviewClick(e: MouseEvent) {
  if (editDeleted.value) return
  const t = e.target as HTMLElement | null
  if (!t || t.tagName !== 'IMG') {
    closeMdImgMenu()
    return
  }
  const img = t as HTMLImageElement
  const src = imageSrcKey(img.currentSrc || img.src || '')
  if (!src) return
  e.preventDefault()
  e.stopPropagation()
  // fixed 定位，避免嵌套滚动容器偏移
  mdImgMenu.value = {
    visible: true,
    x: Math.min(e.clientX + 8, window.innerWidth - 280),
    y: Math.min(e.clientY + 8, window.innerHeight - 56),
    src
  }
}

function applyMdImageWidth(pct: number) {
  const src = mdImgMenu.value.src
  if (!src) return
  // 支持 ![alt](url) 与 <img> 两种写法
  const nextBody = setMarkdownImageWidth(mdBodyText.value || '', src, pct)
  mdBodyText.value = nextBody
  syncMarkdownFromParts()
  dirty.value = true
  saveHint.value = '未保存'
  closeMdImgMenu()
  void renderMarkdownPreview(editContent.value)
  message.success(`已设为宽度 ${pct}%`)
}

function openCreateNote() {
  if (trashMode.value) {
    exitTrashMode()
  }
  searchMode.value = false
  createFormatOpen.value = true
}

function openCreateNoteInFolder(folderId: string) {
  activeFolderId.value = folderId
  filterCategoryId.value = folderId
  expandedIds.value = new Set([...expandedIds.value, folderId])
  openCreateNote()
}

function openCreateNoteWith(format: KbContentFormat) {
  createFormatOpen.value = false
  if (trashMode.value) {
    exitTrashMode()
  }
  void createNote(format)
}

function toggleExpand(folderId: string) {
  const next = new Set(expandedIds.value)
  if (next.has(folderId)) next.delete(folderId)
  else next.add(folderId)
  expandedIds.value = next
}

function onTreeRowClick(row: FlatTreeRow) {
  if (row.type === 'folder') {
    // 离开编辑时丢弃未保存的空草稿，避免后续误创建空文档
    discardBlankDraftIfNeeded()
    activeFolderId.value = row.id
    filterCategoryId.value = row.id
    // 单击文件夹：展开并显示夹内概览（不打开文档）
    if (!expandedIds.value.has(row.id)) {
      expandedIds.value = new Set([...expandedIds.value, row.id])
    }
    selectedId.value = null
    isCreating.value = false
    return
  }
  activeFolderId.value = row.parentId || null
  void selectNote(row.id)
}

/**
 * 点文件夹等离开编辑时：丢掉未保存的新建草稿（永不因树操作自动 create）。
 */
function discardBlankDraftIfNeeded() {
  const orphanDirty = !selectedId.value && dirty.value
  if (!isCreating.value && !orphanDirty) return
  if (
    dirty.value &&
    !isBlankDraftContent(editContent.value, editFormat.value)
  ) {
    message.info('未保存的新建草稿已取消')
  }
  resetEditorToIdle()
}

function resetEditorToIdle() {
  isCreating.value = false
  selectedId.value = null
  dirty.value = false
  saveHint.value = ''
  editContent.value = ''
  editTitle.value = ''
  mdTitleLine.value = ''
  mdBodyText.value = ''
  previewHtml.value = ''
  fastShellHtml.value = ''
  richEditorActive.value = false
  htmlShellVisible.value = false
  filePanelActive.value = false
  pendingFileIds.value = []
}

const moveModalTitle = computed(
  () => `移动「${moveModalItemName.value || '项目'}」`
)

function isInvalidMoveTarget(folderId: string): boolean {
  if (moveModalType.value !== 'folder' || !moveModalItemId.value) return false
  // 不能移到自身
  if (folderId === moveModalItemId.value) return true
  // 不能移到自己的子孙（简单：展开路径上 id 出现在 flatCategories 后代）
  // 用 parent 链反查：目标的祖先含自身则非法
  const parentMap = new Map<string, string | null | undefined>()
  const walk = (nodes: KbExplorerNode[], parent: string | null) => {
    for (const n of nodes) {
      if (n.type === 'folder') {
        parentMap.set(n.id, parent)
        if (n.children?.length) walk(n.children, n.id)
      }
    }
  }
  walk(treeRoots.value, null)
  let cur: string | null | undefined = folderId
  let guard = 0
  while (cur && guard++ < 32) {
    if (cur === moveModalItemId.value) return true
    cur = parentMap.get(cur) ?? null
  }
  return false
}

function openMoveDialog(type: 'folder' | 'note', row: { id: string; name: string }) {
  moveModalType.value = type
  moveModalItemId.value = row.id
  moveModalItemName.value = row.name
  moveTargetId.value = null
  moveModalOpen.value = true
}

async function submitMoveModal() {
  if (!moveModalItemId.value) return
  moveModalLoading.value = true
  try {
    await kbApi.treeMove({
      type: moveModalType.value,
      id: moveModalItemId.value,
      ...(moveTargetId.value
        ? { targetFolderId: moveTargetId.value }
        : { clearToRoot: true })
    })
    message.success('已移动')
    moveModalOpen.value = false
    if (moveTargetId.value) {
      expandedIds.value = new Set([...expandedIds.value, moveTargetId.value])
    }
    await Promise.all([loadCategories(), loadTree({ keepExpanded: true })])
  } catch (e: any) {
    message.error(e?.message || '移动失败')
  } finally {
    moveModalLoading.value = false
  }
}

async function moveNoteToRoot(noteId: string) {
  try {
    await kbApi.treeMove({ type: 'note', id: noteId, clearToRoot: true })
    message.success('已移到未归档')
    await loadTree({ keepExpanded: true })
  } catch (e: any) {
    message.error(e?.message || '移动失败')
  }
}

function onTreeDragStart(e: DragEvent, row: FlatTreeRow) {
  dragPayload.value = {
    key: row.key,
    type: row.type,
    id: row.id,
    parentId: row.parentId,
    name: row.name
  }
  e.dataTransfer?.setData('text/plain', row.key)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
  // 自定义幽灵图（卡片样式），替代浏览器默认半透明截图
  dragGhostCleanup?.()
  dragGhostCleanup = applyDragGhost(e, {
    name: row.name,
    kind: row.type,
    pinned: row.pinned
  })
}

function onTreeDragEnd() {
  dragPayload.value = null
  dropTargetKey.value = null
  dragGhostCleanup?.()
  dragGhostCleanup = null
  cleanupDragGhost()
}

function onTreeDragOver(_e: DragEvent, row: FlatTreeRow) {
  if (!dragPayload.value) return
  if (row.type === 'folder') {
    // 文件夹不能拖到自己或子孙
    if (dragPayload.value.type === 'folder' && dragPayload.value.id === row.id) {
      dropTargetKey.value = null
      return
    }
    dropTargetKey.value = row.key
  } else if (row.type === 'note' && dragPayload.value.type === row.type) {
    // 同类型文档：作为同级重排指示（高亮该行）
    dropTargetKey.value = row.key
  } else {
    dropTargetKey.value = null
  }
}

function onTreeDragLeave(row: FlatTreeRow) {
  if (dropTargetKey.value === row.key) dropTargetKey.value = null
}

function onDragOverRoot() {
  if (dragPayload.value) dropTargetKey.value = 'root'
}

function onDragLeaveRoot() {
  if (dropTargetKey.value === 'root') dropTargetKey.value = null
}

async function onDropRoot() {
  const src = dragPayload.value
  dropTargetKey.value = null
  dragPayload.value = null
  if (!src) return
  try {
    await kbApi.treeMove({ type: src.type, id: src.id, clearToRoot: true })
    message.success('已移到根目录')
    await Promise.all([loadCategories(), loadTree({ keepExpanded: true })])
  } catch (e: any) {
    message.error(e?.message || '移动失败')
  }
}

async function onTreeDrop(_e: DragEvent, row: FlatTreeRow) {
  const src = dragPayload.value
  dropTargetKey.value = null
  dragPayload.value = null
  if (!src) return
  if (src.key === row.key) return

  try {
    if (row.type === 'folder') {
      // 放入目标文件夹
      if (src.type === 'folder' && src.id === row.id) return
      await kbApi.treeMove({
        type: src.type,
        id: src.id,
        targetFolderId: row.id
      })
      expandedIds.value = new Set([...expandedIds.value, row.id])
      message.success('已移动')
      await Promise.all([loadCategories(), loadTree({ keepExpanded: true })])
      return
    }

    // 拖到文档上：同类型 → 重排（把 src 插到 row 之前）
    if (src.type === row.type) {
      // 若拖到不同父级的文档上，先移动到该父级再重排
      const srcParent = src.parentId ?? null
      const dstParent = row.parentId ?? null
      if (srcParent !== dstParent) {
        if (dstParent) {
          await kbApi.treeMove({ type: src.type, id: src.id, targetFolderId: dstParent })
        } else {
          await kbApi.treeMove({ type: src.type, id: src.id, clearToRoot: true })
        }
      }
      const siblings = collectSiblingIds(src.type, dstParent).filter((id) => id !== src.id)
      const idx = siblings.indexOf(row.id)
      const ordered =
        idx < 0 ? [...siblings, src.id] : [...siblings.slice(0, idx), src.id, ...siblings.slice(idx)]
      await kbApi.treeReorder({
        type: src.type,
        orderedIds: ordered,
        ...(dstParent ? { parentFolderId: dstParent } : { clearParent: true })
      })
      message.success('已调整顺序')
      await loadTree({ keepExpanded: true })
    }
  } catch (e: any) {
    message.error(e?.message || '操作失败')
  }
}

/** 收集某父级下同类型节点 id（当前树序） */
function collectSiblingIds(type: 'folder' | 'note', parentId: string | null): string[] {
  const ids: string[] = []
  if (parentId == null) {
    for (const n of treeRoots.value) {
      if (n.type === type) ids.push(n.id)
    }
    return ids
  }
  const folder = findExplorerNode(treeRoots.value, parentId, 'folder')
  if (!folder?.children) return ids
  for (const c of folder.children) {
    if (c.type === type) ids.push(c.id)
  }
  return ids
}

/** Markdown ↔ HTML 内容转换（显式「转为…」时使用） */
async function convertContent(
  from: KbContentFormat,
  to: KbContentFormat,
  content: string
): Promise<string> {
  const src = content || ''
  if (from === to) return src
  if (from === 'markdown' && to === 'html') {
    const render = await ensureMd()
    return render(src)
  }
  if (from === 'html' && to === 'markdown') {
    return htmlToMarkdownLite(src)
  }
  return src
}

function confirmConvertFormat() {
  if (editDeleted.value) return
  const from = editFormat.value
  const to: KbContentFormat = from === 'html' ? 'markdown' : 'html'
  const labelFrom = from === 'html' ? '富文本' : 'Markdown'
  const labelTo = to === 'html' ? '富文本' : 'Markdown'
  Modal.confirm({
    title: `转为${labelTo}？`,
    content: `将把当前「${labelFrom}」内容转换为「${labelTo}」。复杂排版（表格/部分样式）可能略有损失，建议转换后检查再保存。`,
    okText: '转换',
    cancelText: '取消',
    async onOk() {
      applying.value = true
      try {
        // 先刷出编辑器未防抖内容
        richEditorRef.value?.flushEmit?.()
        // Markdown 编辑区先合并
        if (from === 'markdown') syncMarkdownFromParts()
        const converted = await convertContent(from, to, editContent.value)
        editFormat.value = to
        if (to === 'markdown') {
          loadMarkdownParts(
            ensureMarkdownHasTitle(converted, extractTitle(converted, 'html'))
          )
          viewMode.value = 'split'
          richEditorActive.value = false
          htmlShellVisible.value = false
          scheduleMarkdownPreview()
        } else {
          const html = ensureHtmlHasTitle(converted, mdTitleLine.value || editTitle.value)
          editContent.value = html
          editTitle.value = extractTitle(html, 'html')
          updateFastShell(html, 'html')
          htmlShellVisible.value = true
          scheduleRichEditorBoot(++openSeq)
        }
        dirty.value = true
        saveHint.value = '未保存（已转换格式）'
        message.success(`已转为${labelTo}，请检查内容后保存`)
      } catch (e: any) {
        message.error(e?.message || '转换失败')
      } finally {
        applying.value = false
      }
    }
  })
}

function parseNoteTime(t?: string) {
  if (!t) return null
  const d = dayjs(t)
  return d.isValid() ? d : null
}

function formatTime(t?: string) {
  const d = parseNoteTime(t)
  return d ? d.format('MM-DD HH:mm') : ''
}

/** 列表展示：今天只显示时刻，本年 MM-DD，跨年带年份 */
function formatListTime(t?: string) {
  const d = parseNoteTime(t)
  if (!d) return ''
  const now = dayjs()
  if (d.isSame(now, 'day')) return `今天 ${d.format('HH:mm')}`
  if (d.isSame(now, 'year')) return d.format('MM-DD HH:mm')
  return d.format('YYYY-MM-DD')
}

function formatTimeFull(t?: string) {
  const d = parseNoteTime(t)
  return d ? `最后修改 ${d.format('YYYY-MM-DD HH:mm:ss')}` : ''
}

/** 置顶优先，再按最后修改时间降序（与后端一致，前端再排一次更稳） */
function sortNotesForList(items: KbNoteItem[]): KbNoteItem[] {
  return [...items].sort((a, b) => {
    const pin = Number(!!b.pinned) - Number(!!a.pinned)
    if (pin !== 0) return pin
    const ta = parseNoteTime(a.updatedAt)?.valueOf() ?? 0
    const tb = parseNoteTime(b.updatedAt)?.valueOf() ?? 0
    if (tb !== ta) return tb - ta
    // id 降序作稳定次序
    if (a.id === b.id) return 0
    return a.id < b.id ? 1 : -1
  })
}

async function loadCategories() {
  const res = await kbApi.listCategories()
  categories.value = res.data || []
}

async function loadTree(opts?: { keepExpanded?: boolean }) {
  treeLoading.value = true
  try {
    const res = await kbApi.getExplorerTree()
    const data = res.data
    treeRoots.value = data?.roots || []
    treeMeta.value = {
      folderCount: data?.folderCount ?? 0,
      noteCount: data?.noteCount ?? 0
    }
    // 首次：默认展开根层文件夹
    if (!opts?.keepExpanded && expandedIds.value.size === 0) {
      const roots = treeRoots.value.filter((n) => n.type === 'folder').map((n) => n.id)
      expandedIds.value = new Set(roots.slice(0, 20))
    }
  } finally {
    treeLoading.value = false
  }
}

async function loadTags() {
  const res = await kbApi.listTags()
  tags.value = res.data || []
}

async function reloadTrashCount() {
  try {
    const res = await kbApi.trashCount()
    trashCount.value = Number(res.data?.count ?? 0)
  } catch {
    /* ignore */
  }
}

/** 列表查询：回收站 / 搜索 / 标签 / 最近 / 置顶 */
async function reloadNotes() {
  listLoading.value = true
  try {
    const inListShortcut = !!listViewMode.value
    const res = await kbApi.listNotes({
      page: page.value,
      size: pageSize,
      categoryId:
        !trashMode.value &&
        !searchMode.value &&
        !inListShortcut &&
        filterCategoryId.value &&
        filterCategoryId.value !== '__none__'
          ? filterCategoryId.value
          : undefined,
      uncategorized:
        !trashMode.value &&
        !searchMode.value &&
        !inListShortcut &&
        filterCategoryId.value === '__none__',
      tagId:
        !trashMode.value && !inListShortcut
          ? filterTagId.value || undefined
          : undefined,
      keyword: keyword.value || undefined,
      onlyDeleted: trashMode.value,
      onlyPinned: !trashMode.value && listViewMode.value === 'pinned'
    })
    notes.value = sortNotesForList(res.data?.items || [])
    total.value = res.data?.total ?? 0
    if (trashMode.value) {
      trashCount.value = total.value
    } else {
      await reloadTrashCount()
    }
  } finally {
    listLoading.value = false
  }
}

async function refreshExplorer() {
  if (trashMode.value || searchMode.value || filterTagId.value || listViewMode.value) {
    await reloadNotes()
  } else {
    await loadTree({ keepExpanded: true })
  }
  await reloadTrashCount()
}

function onSearch() {
  const kw = keyword.value.trim()
  if (!kw) {
    // 回收站内清空关键词：重新加载回收站列表
    if (trashMode.value) {
      page.value = 0
      void reloadNotes()
      return
    }
    clearSearch()
    return
  }
  // 回收站内搜索：保持 trashMode，只按关键词过滤已删文档
  if (!trashMode.value) {
    filterTagId.value = null
    listViewMode.value = null
    searchMode.value = true
  }
  page.value = 0
  selectedId.value = null
  isCreating.value = false
  void reloadNotes()
}

function clearSearch() {
  keyword.value = ''
  searchMode.value = false
  notes.value = []
  total.value = 0
  if (trashMode.value) {
    void reloadNotes()
  } else if (listViewMode.value) {
    void reloadNotes()
  } else {
    void loadTree({ keepExpanded: true })
  }
}

function openRecentList() {
  trashMode.value = false
  searchMode.value = false
  filterTagId.value = null
  filterCategoryId.value = null
  listViewMode.value = 'recent'
  page.value = 0
  selectedId.value = null
  isCreating.value = false
  void reloadNotes()
}

function openPinnedList() {
  trashMode.value = false
  searchMode.value = false
  filterTagId.value = null
  filterCategoryId.value = null
  listViewMode.value = 'pinned'
  page.value = 0
  selectedId.value = null
  isCreating.value = false
  void reloadNotes()
}

function clearListView() {
  listViewMode.value = null
  notes.value = []
  total.value = 0
  void loadTree({ keepExpanded: true })
}

async function publishCurrentNoteToBlog() {
  if (!selectedId.value || isCreating.value) {
    message.warning('请先保存笔记再发布')
    return
  }
  if (publishingBlog.value) return

  const updating = !!editHaloPermalink.value
  const title = (editTitle.value || '未命名笔记').trim()
  Modal.confirm({
    title: updating ? '更新到博客？' : '发布到博客？',
    content: updating
      ? `将把「${title}」的最新内容同步到云端博客（覆盖该文）。`
      : `将把「${title}」发布到云端博客。笔记仍保存在本机/工具台知识库。`,
    okText: updating ? '更新' : '发布',
    cancelText: '取消',
    async onOk() {
      publishingBlog.value = true
      try {
        if (dirty.value) {
          await saveNote(true)
        }
        if (!selectedId.value) {
          message.warning('请先保存笔记再发布')
          return
        }
        const res = await kbApi.publishNoteToBlog(selectedId.value)
        const note = res.data
        if (note?.haloPermalink) {
          editHaloPermalink.value = note.haloPermalink
        }
        if (note?.unresolvedMedia) {
          message.warning('已发布，但正文含知识库私有附件，博客读者可能看不到图')
        } else {
          message.success(updating ? '已同步到博客' : '已发布到博客')
        }
        if (note?.haloPermalink) {
          window.open(note.haloPermalink, '_blank', 'noopener')
        }
      } catch (e: any) {
        message.error(e?.message || '发布失败')
      } finally {
        publishingBlog.value = false
      }
    }
  })
}

async function exportCurrentNote() {
  if (!selectedId.value || isCreating.value) return
  try {
    await kbApi.exportNoteMarkdown(selectedId.value, editTitle.value)
    message.success('已导出 Markdown')
  } catch (e: any) {
    message.error(e?.message || '导出失败')
  }
}

async function duplicateCurrentNote() {
  if (!selectedId.value || isCreating.value) return
  try {
    const res = await kbApi.duplicateNote(selectedId.value)
    message.success('已复制')
    await refreshExplorer()
    if (res.data?.id) {
      await selectNote(res.data.id)
    }
  } catch (e: any) {
    message.error(e?.message || '复制失败')
  }
}

async function openRevisions() {
  if (!selectedId.value || isCreating.value) return
  revisionModalOpen.value = true
  revisionLoading.value = true
  revisions.value = []
  try {
    const res = await kbApi.listRevisions(selectedId.value)
    revisions.value = res.data || []
  } catch (e: any) {
    message.error(e?.message || '加载版本失败')
  } finally {
    revisionLoading.value = false
  }
}

async function restoreRevision(revisionId: string) {
  if (!selectedId.value) return
  revisionRestoring.value = revisionId
  try {
    const res = await kbApi.restoreRevision(selectedId.value, revisionId)
    message.success('已恢复该版本')
    revisionModalOpen.value = false
    await selectNote(res.data.id)
    await refreshExplorer()
  } catch (e: any) {
    message.error(e?.message || '恢复失败')
  } finally {
    revisionRestoring.value = null
  }
}

function onResultPageChange(p: number) {
  page.value = Math.max(0, p - 1)
  void reloadNotes()
}

function clearTagFilter() {
  filterTagId.value = null
  notes.value = []
  total.value = 0
  void loadTree({ keepExpanded: true })
}

function exitTrashMode() {
  if (!trashMode.value) return
  trashMode.value = false
  selectedId.value = null
  isCreating.value = false
  page.value = 0
}

function toggleTrashMode() {
  trashMode.value = !trashMode.value
  selectedId.value = null
  isCreating.value = false
  filterCategoryId.value = null
  filterTagId.value = null
  listViewMode.value = null
  searchMode.value = false
  page.value = 0
  keyword.value = ''
  if (trashMode.value) {
    reloadNotes()
  } else {
    void loadTree({ keepExpanded: true })
  }
}

function toggleTagFilter(id: string) {
  exitTrashMode()
  searchMode.value = false
  listViewMode.value = null
  keyword.value = ''
  if (filterTagId.value === id) {
    clearTagFilter()
    return
  }
  filterTagId.value = id
  filterCategoryId.value = null
  page.value = 0
  selectedId.value = null
  isCreating.value = false
  reloadNotes()
}

async function selectNote(id: string) {
  // 仅自动保存「已有文档」的修改；新建空草稿绝不因切换而入库
  if (dirty.value && !editDeleted.value && selectedId.value && !isCreating.value) {
    try {
      await saveNote(true)
    } catch {
      /* keep going */
    }
  } else if (isCreating.value || (!selectedId.value && dirty.value)) {
    if (
      dirty.value &&
      !isBlankDraftContent(editContent.value, editFormat.value)
    ) {
      message.info('未保存的新建草稿已取消')
    }
    // 丢弃新建态，避免 saveNote 因 !selectedId 误创建
    isCreating.value = false
    dirty.value = false
    saveHint.value = ''
  }
  const seq = ++openSeq
  clearBootTimers()
  isCreating.value = false
  selectedId.value = id
  pendingFileIds.value = []
  contentLoading.value = true
  richEditorActive.value = false
  filePanelActive.value = false
  htmlShellVisible.value = true
  fastShellHtml.value = ''

  // 树中滚到该文档行（虚拟列表）
  scrollTreeToNote(id)

  // 先用列表/树缓存标题占位
  const cachedList = notes.value.find((n) => n.id === id)
  const cachedTree = findExplorerNode(treeRoots.value, id, 'note')
  if (cachedList || cachedTree) {
    applying.value = true
    editTitle.value = cachedList?.title || cachedTree?.name || ''
    editContent.value = ''
    editFormat.value =
      (cachedList?.contentFormat || cachedTree?.contentFormat) === 'markdown'
        ? 'markdown'
        : 'html'
    editCategoryId.value = cachedList?.categoryId || cachedTree?.parentId || undefined
    editTagIds.value = (cachedList?.tags || []).map((t) => t.id)
    editPinned.value = !!(cachedList?.pinned ?? cachedTree?.pinned)
    editDeleted.value = !!cachedList?.deleted
    editHaloPermalink.value = cachedList?.haloPermalink || ''
    editUpdatedAt.value = cachedList?.updatedAt || cachedTree?.updatedAt
    saveHint.value = '加载正文中…'
    dirty.value = false
    Promise.resolve().then(() => {
      applying.value = false
    })
  }

  const t0 = performance.now()
  try {
    const res = (await kbApi.getNote(id)) as {
      data: import('@/api/kb.api').KbNoteItem
      headers?: Record<string, string>
    }
    if (seq !== openSeq) return
    const ms = Math.round(performance.now() - t0)
    const chars = res.data?.content?.length ?? 0
    const headers = res.headers || {}
    console.info(
      `[kb] GET /notes/${id} clientWait=${ms}ms contentChars=${chars}` +
        (headers['x-kb-query-ms'] != null ? ` serverQuery=${headers['x-kb-query-ms']}ms` : '') +
        (headers['x-kb-db-ms'] != null ? ` db=${headers['x-kb-db-ms']}ms` : '')
    )
    applyNote(res.data, seq)
    dirty.value = false
  } catch (e: any) {
    if (seq !== openSeq) return
    contentLoading.value = false
    message.error(e?.message || '加载笔记失败')
  }
}

function applyNote(n: KbNoteItem, seq = openSeq) {
  if (seq !== openSeq) return
  applying.value = true
  contentLoading.value = false
  editTitle.value = n.title || ''
  const format: KbContentFormat = n.contentFormat === 'markdown' ? 'markdown' : 'html'
  let cleanContent =
    format === 'markdown' ? n.content || '' : stripKbMediaTokens(n.content || '')
  // 旧数据：标题在独立字段时，补到正文首行 H1 / # 标题
  cleanContent =
    format === 'markdown'
      ? ensureMarkdownHasTitle(cleanContent, n.title)
      : ensureHtmlHasTitle(cleanContent, n.title)
  editFormat.value = format
  if (format === 'markdown') {
    loadMarkdownParts(cleanContent)
    const len = cleanContent.length
    viewMode.value = len > 8000 ? 'edit' : 'split'
  } else {
    editContent.value = cleanContent
    editTitle.value = extractTitle(cleanContent, format) || n.title || '未命名笔记'
    mdTitleLine.value = editTitle.value
    mdBodyText.value = ''
  }
  editCategoryId.value = n.categoryId || undefined
  editTagIds.value = (n.tags || []).map((t) => t.id)
  editPinned.value = !!n.pinned
  editDeleted.value = !!n.deleted
  editHaloPermalink.value = n.haloPermalink || ''
  editUpdatedAt.value = n.updatedAt
  saveHint.value = editFormat.value === 'html' && !n.deleted ? '编辑器准备中…' : ''
  dirty.value = false

  if (editFormat.value === 'html') {
    // ① 立刻填充快速预览（轻量 v-html）
    updateFastShell(cleanContent, 'html')
    htmlShellVisible.value = true
    // ② 空闲后再挂 WangEditor
    scheduleRichEditorBoot(seq)
  } else {
    richEditorActive.value = false
    htmlShellVisible.value = false
    fastShellHtml.value = ''
    filePanelActive.value = true
    if (viewMode.value !== 'edit') {
      scheduleMarkdownPreview()
    } else {
      previewHtml.value = ''
    }
  }

  Promise.resolve().then(() => {
    applying.value = false
  })
}

async function createNote(format: KbContentFormat = 'html') {
  if (dirty.value && !editDeleted.value) {
    try {
      // 保存前显式 flush，卸载编辑器时不再回写
      richEditorRef.value?.flushEmit?.()
      await saveNote(true)
    } catch {
      /* ignore */
    }
  } else {
    // 即使未标脏，也丢掉编辑器防抖缓冲，避免 unmount 残留
    richEditorRef.value?.flushEmit?.()
  }

  const seq = ++openSeq
  clearBootTimers()
  if (mdPreviewTimer) {
    clearTimeout(mdPreviewTimer)
    mdPreviewTimer = null
  }

  // 先卸掉富文本编辑器，再清空内容，避免 unmount 时序问题
  richEditorActive.value = false
  htmlShellVisible.value = false
  filePanelActive.value = false

  applying.value = true
  isCreating.value = true
  selectedId.value = null
  pendingFileIds.value = []
  contentLoading.value = false
  editTitle.value = '未命名笔记'
  editHaloPermalink.value = ''
  if (format === 'html') {
    editContent.value = emptyHtmlDoc()
    mdTitleLine.value = '未命名笔记'
    mdBodyText.value = ''
  } else {
    loadMarkdownParts(emptyMarkdownDoc())
  }
  editFormat.value = format
  viewMode.value = format === 'markdown' ? 'split' : 'edit'
  editCategoryId.value =
    activeFolderId.value ||
    (filterCategoryId.value && filterCategoryId.value !== '__none__'
      ? filterCategoryId.value
      : undefined)
  editTagIds.value = filterTagId.value ? [filterTagId.value] : []
  editPinned.value = false
  editDeleted.value = false
  editUpdatedAt.value = undefined
  dirty.value = true
  saveHint.value = '未保存'
  // 必须清掉上一条的预览/壳层，否则 Markdown 分栏会显示旧 HTML
  previewHtml.value = ''
  fastShellHtml.value = ''

  await Promise.resolve() // 等 v-if 卸掉旧 RichEditor

  if (format === 'html') {
    richEditorActive.value = true
    htmlShellVisible.value = false
    filePanelActive.value = true
  } else {
    richEditorActive.value = false
    htmlShellVisible.value = false
    filePanelActive.value = true
    // 空文档预览
    if (viewMode.value !== 'edit') {
      previewHtml.value = ''
    }
  }

  Promise.resolve().then(() => {
    applying.value = false
  })
  void seq
}

async function saveNote(silent = false) {
  if (editDeleted.value) return
  // 保存前把编辑器防抖中的最新内容刷出来
  richEditorRef.value?.flushEmit?.()

  // Markdown 先合并，便于判断是否空草稿
  if (editFormat.value === 'markdown') {
    syncMarkdownFromParts()
  }

  // 关键规则：只有明确「新建中」才 create；禁止 !selectedId 误创建
  const creating = isCreating.value
  const existingId = selectedId.value
  if (!creating && !existingId) {
    dirty.value = false
    saveHint.value = ''
    return
  }

  // 新建且仍是空白：不写库（树操作/切换时也不会留下空文件）
  if (
    creating &&
    isBlankDraftContent(editContent.value, editFormat.value) &&
    silent
  ) {
    return
  }
  if (creating && isBlankDraftContent(editContent.value, editFormat.value) && !silent) {
    message.warning('请先写点内容再保存')
    return
  }

  clearDebouncedAutoSave()
  saving.value = true
  saveHint.value = '保存中…'
  try {
    // 正文只存干净媒体路径（HTML 属性 + Markdown ![]() 均去 token）
    let contentToSave = stripKbMediaTokensAll(editContent.value || '')
    // 保证存库时正文含首行标题
    contentToSave =
      editFormat.value === 'html'
        ? ensureHtmlHasTitle(contentToSave, editTitle.value)
        : ensureMarkdownHasTitle(contentToSave, editTitle.value)
    const titleFromDoc = extractTitle(contentToSave, editFormat.value)
    editTitle.value = titleFromDoc
    const body = {
      title: titleFromDoc,
      content: contentToSave,
      contentFormat: editFormat.value,
      categoryId: editCategoryId.value || null,
      clearCategory: !editCategoryId.value,
      tagIds: editTagIds.value,
      pinned: editPinned.value
    }
    let note: KbNoteItem
    if (creating) {
      const res = await kbApi.createNote({
        title: body.title,
        content: body.content,
        contentFormat: body.contentFormat,
        categoryId: body.categoryId,
        tagIds: body.tagIds,
        pinned: body.pinned
      })
      note = res.data
      isCreating.value = false
      selectedId.value = note.id
    } else {
      const res = await kbApi.updateNote(existingId!, body)
      note = res.data
    }
    if (note.id) {
      await bindPendingFiles(note.id)
    }
    // 保存后不重挂编辑器，只合并元数据，避免 setHtml 卡顿
    applying.value = true
    if (note.content != null) {
      const fmt: KbContentFormat =
        note.contentFormat === 'markdown' ? 'markdown' : 'html'
      let clean =
        fmt === 'markdown' ? note.content : stripKbMediaTokens(note.content)
      clean =
        fmt === 'markdown'
          ? ensureMarkdownHasTitle(clean, note.title)
          : ensureHtmlHasTitle(clean, note.title)
      // 与编辑器 lastEmitted 对齐：内容相同则 RichEditor 不会 setHtml
      editContent.value = clean
      editTitle.value = extractTitle(clean, fmt) || note.title || '未命名笔记'
      if (fmt === 'html') {
        updateFastShell(clean, 'html')
      }
    } else if (note.title) {
      editTitle.value = note.title
    }
    if (note.contentFormat) {
      editFormat.value = note.contentFormat === 'markdown' ? 'markdown' : 'html'
    }
    editCategoryId.value = note.categoryId || undefined
    editTagIds.value = (note.tags || []).map((t) => t.id)
    editPinned.value = !!note.pinned
    editDeleted.value = !!note.deleted
    editUpdatedAt.value = note.updatedAt || editUpdatedAt.value
    isCreating.value = false
    selectedId.value = note.id
    contentLoading.value = false
    // 保存后保持编辑器，不闪快速壳
    if (editFormat.value === 'html' && !editDeleted.value) {
      richEditorActive.value = true
      htmlShellVisible.value = false
      filePanelActive.value = true
    }
    dirty.value = false
    saveHint.value = '已保存'
    Promise.resolve().then(() => {
      applying.value = false
    })
    if (!silent) message.success('已保存')
    // 展开文档所在文件夹
    if (note.categoryId) {
      expandedIds.value = new Set([...expandedIds.value, note.categoryId])
      activeFolderId.value = note.categoryId
    }
    // 新建后必须刷新树；普通静默保存只改标题，避免整树重载闪动
    // 显式保存也刷新（置顶等元数据）；后端排序已与 updatedAt 解耦，不会「点谁谁置顶」
    if (creating || !silent) {
      void Promise.all([
        refreshExplorer(),
        loadTags(),
        filePanelRef.value?.reload?.() ?? Promise.resolve()
      ])
    } else {
      patchTreeNoteTitle(note.id, note.title || '未命名笔记')
      void (filePanelRef.value?.reload?.() ?? Promise.resolve())
    }
  } catch (e: any) {
    saveHint.value = '保存失败，可 Ctrl+S 重试'
    if (!silent) message.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function onKbKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
    e.preventDefault()
    void saveNote(false)
  }
}

function onBeforeUnload(e: BeforeUnloadEvent) {
  if (dirty.value && !editDeleted.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

/** 不重排整树，只更新某文档显示名 */
function patchTreeNoteTitle(noteId: string, title: string) {
  const walk = (nodes: KbExplorerNode[]): boolean => {
    for (const n of nodes) {
      if (n.type === 'note' && n.id === noteId) {
        n.name = title
        return true
      }
      if (n.children?.length && walk(n.children)) return true
    }
    return false
  }
  // 触发响应式：浅拷贝 roots
  const roots = treeRoots.value
  if (walk(roots)) {
    treeRoots.value = roots.slice()
  }
}

function autoSave() {
  if (applying.value || editDeleted.value || saving.value) return
  if (!dirty.value) return
  // 仅更新已有文档；新建空草稿不在 blur 时入库
  if (selectedId.value && !isCreating.value) {
    void saveNote(true)
  }
}

function markMetaDirtyAndSave() {
  if (applying.value || editDeleted.value) return
  if (!(selectedId.value || isCreating.value)) return
  dirty.value = true
  saveHint.value = '未保存'
  // 新建空白时不因打标签而 create 空文档
  if (isCreating.value && isBlankDraftContent(editContent.value, editFormat.value)) {
    return
  }
  if (selectedId.value || isCreating.value) {
    void saveNote(true)
  }
}

function toggleDocTag(tagId: string) {
  if (editDeleted.value) return
  const set = new Set(editTagIds.value)
  if (set.has(tagId)) set.delete(tagId)
  else set.add(tagId)
  editTagIds.value = [...set]
  markMetaDirtyAndSave()
}

function removeDocTag(tagId: string) {
  if (editDeleted.value) return
  editTagIds.value = editTagIds.value.filter((id) => id !== tagId)
  markMetaDirtyAndSave()
}

async function createAndApplyTag() {
  const name = tagPickerQuery.value.trim()
  if (!name || editDeleted.value) return
  const existing = tags.value.find((t) => t.name.toLowerCase() === name.toLowerCase())
  if (existing) {
    if (!editTagIds.value.includes(existing.id)) {
      editTagIds.value = [...editTagIds.value, existing.id]
      markMetaDirtyAndSave()
    }
    tagPickerQuery.value = ''
    return
  }
  try {
    const res = await kbApi.createTag(name)
    const tag = res.data
    await loadTags()
    if (tag?.id && !editTagIds.value.includes(tag.id)) {
      editTagIds.value = [...editTagIds.value, tag.id]
      markMetaDirtyAndSave()
    }
    tagPickerQuery.value = ''
    message.success(`已添加 #${name}`)
  } catch (e: any) {
    message.error(e?.message || '创建标签失败')
  }
}

// 置顶变更标脏；标签由 toggle/remove 自行保存
watch(editPinned, () => {
  if (applying.value) return
  if (selectedId.value || isCreating.value) {
    dirty.value = true
    saveHint.value = '未保存'
  }
})

async function togglePin() {
  editPinned.value = !editPinned.value
  dirty.value = true
  // 非静默：刷新树以更新置顶排序（仍不按 updatedAt 跳动）
  await saveNote(false)
}

/** 左侧目录：删除文档 */
function confirmDeleteNote(row: { id: string; name: string }) {
  Modal.confirm({
    title: `删除「${row.name || '未命名笔记'}」？`,
    content: '将移入回收站，可稍后恢复。',
    okText: '移入回收站',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    async onOk() {
      await deleteNoteById(row.id)
    }
  })
}

async function deleteCurrent() {
  const id = selectedId.value
  if (!id) return
  await deleteNoteById(id)
}

async function deleteNoteById(id: string) {
  if (!id) return
  // 阻止编辑器 blur/防抖保存在软删之后把笔记「救回」正常列表
  applying.value = true
  dirty.value = false
  try {
    await kbApi.deleteNote(id)
    notes.value = notes.value.filter((n) => n.id !== id)
    total.value = Math.max(0, total.value - (trashMode.value ? 0 : 1))
    if (!trashMode.value) {
      trashCount.value += 1
    }
    if (selectedId.value === id) {
      selectedId.value = null
      isCreating.value = false
      editContent.value = ''
      saveHint.value = ''
    }
    message.success('已移入回收站')
    await Promise.all([refreshExplorer(), loadTags(), reloadTrashCount()])
  } catch (e: any) {
    message.error(e?.message || '删除失败')
    await refreshExplorer()
  } finally {
    applying.value = false
  }
}

async function restoreCurrent() {
  if (!selectedId.value) return
  saving.value = true
  try {
    const res = await kbApi.restoreNote(selectedId.value)
    message.success('已恢复')
    if (trashMode.value) {
      selectedId.value = null
      await reloadNotes()
    } else {
      applyNote(res.data)
      await Promise.all([refreshExplorer(), loadTags()])
    }
    await reloadTrashCount()
  } finally {
    saving.value = false
  }
}

async function permanentDeleteCurrent() {
  if (!selectedId.value) return
  saving.value = true
  try {
    await kbApi.permanentDeleteNote(selectedId.value)
    message.success('已永久删除（含附件与存储对象）')
    selectedId.value = null
    isCreating.value = false
    dirty.value = false
    await Promise.all([refreshExplorer(), loadTags(), reloadTrashCount()])
  } catch (e: any) {
    message.error(e?.message || '永久删除失败')
  } finally {
    saving.value = false
  }
}

async function emptyTrash() {
  emptying.value = true
  try {
    const res = await kbApi.emptyTrash()
    const n = res.data?.deleted ?? 0
    message.success(n ? `已清空 ${n} 条` : '回收站已空')
    selectedId.value = null
    await reloadNotes()
    await reloadTrashCount()
  } catch (e: any) {
    message.error(e?.message || '清空失败')
  } finally {
    emptying.value = false
  }
}

function openCreateCategory(parentId?: string) {
  catModalParentId.value = parentId || activeFolderId.value || null
  catModalName.value = ''
  catModalOpen.value = true
}

async function submitCategory() {
  const name = catModalName.value.trim()
  if (!name) {
    message.warning('请输入文件夹名称')
    return
  }
  catModalLoading.value = true
  try {
    const res = await kbApi.createCategory({
      name,
      parentId: catModalParentId.value || undefined
    })
    catModalOpen.value = false
    message.success('文件夹已创建')
    const newId = res.data?.id
    if (catModalParentId.value) {
      expandedIds.value = new Set([...expandedIds.value, catModalParentId.value])
    }
    if (newId) {
      expandedIds.value = new Set([...expandedIds.value, newId])
      activeFolderId.value = newId
    }
    await Promise.all([loadCategories(), loadTree({ keepExpanded: true })])
  } finally {
    catModalLoading.value = false
  }
}

function openRenameCategory(node: { id: string; name: string }) {
  renameModalId.value = node.id
  renameModalName.value = node.name
  renameModalOpen.value = true
}

async function submitRenameCategory() {
  if (!renameModalId.value) return
  const name = renameModalName.value.trim()
  if (!name) {
    message.warning('请输入文件夹名称')
    return
  }
  renameModalLoading.value = true
  try {
    await kbApi.updateCategory(renameModalId.value, { name })
    renameModalOpen.value = false
    message.success('已重命名')
    await Promise.all([loadCategories(), loadTree({ keepExpanded: true })])
  } finally {
    renameModalLoading.value = false
  }
}

function confirmDeleteCategory(node: { id: string; name: string }) {
  deleteFolderTarget.value = node
  deleteFolderMode.value = 'reject'
  deleteFolderOpen.value = true
}

const deleteFolderOpen = ref(false)
const deleteFolderLoading = ref(false)
const deleteFolderMode = ref<'reject' | 'orphan' | 'trash'>('reject')
const deleteFolderTarget = ref<{ id: string; name: string } | null>(null)
const deleteFolderModeOptions: {
  value: 'reject' | 'orphan' | 'trash'
  title: string
  desc: string
}[] = [
  { value: 'reject', title: '仅删空夹', desc: '有内容时失败，最安全' },
  { value: 'orphan', title: '内容上移后删', desc: '子项移到父级/未归档' },
  { value: 'trash', title: '进回收站后删', desc: '文档软删，夹物理删除' }
]

async function submitDeleteFolder() {
  if (!deleteFolderTarget.value) return
  deleteFolderLoading.value = true
  try {
    const res = await kbApi.deleteCategory(deleteFolderTarget.value.id, deleteFolderMode.value)
    const name = deleteFolderTarget.value.name
    if (deleteFolderMode.value === 'orphan') {
      message.success(`已删除「${name}」，${res.data?.notesOrphaned ?? 0} 篇文档已上移`)
    } else if (deleteFolderMode.value === 'trash') {
      message.success(
        `已删除 ${res.data?.foldersDeleted ?? 0} 个文件夹，${res.data?.notesTrashed ?? 0} 篇文档进回收站`
      )
    } else {
      message.success('已删除')
    }
    if (
      filterCategoryId.value === deleteFolderTarget.value.id ||
      activeFolderId.value === deleteFolderTarget.value.id
    ) {
      filterCategoryId.value = null
      activeFolderId.value = null
    }
    deleteFolderOpen.value = false
    await Promise.all([loadCategories(), loadTree({ keepExpanded: true }), reloadTrashCount()])
  } catch (e: any) {
    message.error(e?.message || '删除失败')
  } finally {
    deleteFolderLoading.value = false
  }
}

function openCreateTag() {
  tagModalName.value = ''
  tagModalOpen.value = true
}

async function submitTag() {
  const name = tagModalName.value.trim()
  if (!name) {
    message.warning('请输入标签名称')
    return
  }
  tagModalLoading.value = true
  try {
    await kbApi.createTag(name)
    tagModalOpen.value = false
    message.success('标签已创建')
    await loadTags()
  } finally {
    tagModalLoading.value = false
  }
}

/** 小屏禁 Markdown 分栏（窄屏两侧不可用） */
let mobileMq: MediaQueryList | null = null
function applyMobileViewMode() {
  if (typeof window === 'undefined') return
  if (window.matchMedia('(max-width: 768px)').matches && viewMode.value === 'split') {
    viewMode.value = 'edit'
  }
}

onMounted(async () => {
  await Promise.all([loadCategories(), loadTree(), loadTags(), reloadTrashCount()])
  bindTreeViewport()
  applyMobileViewMode()
  mobileMq = window.matchMedia('(max-width: 768px)')
  mobileMq.addEventListener('change', applyMobileViewMode)
  window.addEventListener('keydown', onKbKeydown)
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  clearDebouncedAutoSave()
  unbindTreeViewport()
  dragGhostCleanup?.()
  cleanupDragGhost()
  mobileMq?.removeEventListener('change', applyMobileViewMode)
  mobileMq = null
  window.removeEventListener('keydown', onKbKeydown)
  window.removeEventListener('beforeunload', onBeforeUnload)
})

// 树模式显示时绑定视口（从搜索/回收站切回）
watch(isExplorerTreeMode, (showTree) => {
  if (showTree) bindTreeViewport()
  else unbindTreeViewport()
})

// 新建 Markdown 默认分栏 → 小屏改编辑
watch(viewMode, (mode) => {
  if (mode === 'split') applyMobileViewMode()
})
</script>

<style lang="scss" scoped>
.kb-workspace {
  display: grid;
  /* F1：目录树 + 编辑区两栏 */
  grid-template-columns: minmax(280px, 340px) 1fr;
  gap: 12px;
  height: 100%;
  min-height: 0;
  flex: 1;
}

.kb-sidebar,
.kb-editor-pane {
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 14px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.kb-sidebar {
  position: relative;
}

.quick-views {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 0 12px 8px;
}

.quick-chip {
  border: 1px solid var(--border-color);
  background: var(--surface-2, transparent);
  color: var(--text-secondary, inherit);
  border-radius: 999px;
  font-size: 12px;
  padding: 2px 10px;
  cursor: pointer;
  line-height: 1.6;
}

.quick-chip.on {
  border-color: var(--primary-color, #1677ff);
  color: var(--primary-color, #1677ff);
  background: color-mix(in srgb, var(--primary-color, #1677ff) 12%, transparent);
}

.quick-chip.ghost {
  opacity: 0.85;
}

.side-head-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.editor-status {
  .save-ok {
    color: var(--success-color, #52c41a);
  }
  .save-warn {
    color: var(--warning-color, #faad14);
  }
  .outline-inline {
    margin-left: 12px;
  }
}

.doc-outline {
  border-top: 1px solid var(--border-color);
  max-height: 140px;
  overflow: auto;
  padding: 6px 10px 10px;
  flex-shrink: 0;
}

.outline-title {
  font-size: 11px;
  font-weight: 600;
  opacity: 0.7;
  margin-bottom: 4px;
}

.outline-item {
  display: block;
  width: 100%;
  text-align: left;
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 12px;
  padding: 3px 4px;
  border-radius: 4px;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.outline-item:hover {
  background: var(--surface-2, rgba(0, 0, 0, 0.04));
}

.rev-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 420px;
  overflow: auto;
}

.rev-item {
  padding: 10px 0;
  border-bottom: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rev-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
}

.rev-snip {
  margin: 0;
  font-size: 12px;
  line-height: 1.4;
}

.back-dir-btn {
  padding: 0 4px;
  height: auto;
  font-size: 12px;
  font-weight: 600;
}

.result-empty-side {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 12px;
  font-size: 13px;
  text-align: center;
}

.trash-empty-center {
  .trash-empty-title {
    margin: 0 0 8px;
    font-size: 20px;
    font-weight: 700;
    color: var(--text-primary);
  }

  p {
    margin: 0 0 4px;
  }
}

.trash-empty-icon {
  color: #a8a29e !important;
  opacity: 0.7;
}

.side-search {
  padding: 0 10px 8px;
}

.side-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding-bottom: 8px;

  /* 目录树模式：树内部虚拟滚动，标签贴底 */
  &.is-tree {
    overflow: hidden;
    display: flex;
    flex-direction: column;
    padding-bottom: 0;

    > .explorer-tree {
      flex: 1;
      min-height: 0;
    }

    > .tags-head,
    > .tag-list {
      flex-shrink: 0;
    }

    > .tag-list {
      max-height: 120px;
      overflow: auto;
      margin-bottom: 4px;
    }
  }

  /* 回收站 / 搜索 / 标签结果列表 */
  &.is-list {
    display: flex;
    flex-direction: column;
    overflow: hidden;
    padding-bottom: 0;
  }
}

.result-list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px 6px;
  flex-shrink: 0;
}

.result-list-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--text-secondary);
}

.result-list {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 0 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.result-empty {
  padding: 24px 12px;
}

.result-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--border-color);
  background: var(--surface-2, transparent);
  border-radius: 10px;
  padding: 10px 12px;
  cursor: pointer;
  color: inherit;
  transition: border-color 0.15s, background 0.15s;

  &:hover {
    border-color: color-mix(in srgb, var(--primary-color) 45%, var(--border-color));
  }

  &.active {
    border-color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 10%, transparent);
  }

  &.deleted .result-item-title {
    color: var(--text-secondary);
  }
}

.result-item-top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.result-item-icon {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 14px;
}

.result-item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 650;
  color: var(--text-primary);
}

.result-fmt {
  margin-inline-end: 0;
  font-size: 10px;
  line-height: 16px;
  padding: 0 5px;
  flex-shrink: 0;
}

.result-item-snippet {
  font-size: 12px;
  line-height: 1.45;
  color: var(--text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.result-item-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-secondary);
}

.result-cat {
  padding: 0 6px;
  border-radius: 4px;
  background: var(--surface-3, rgba(0, 0, 0, 0.04));
}

.result-tag {
  opacity: 0.9;
}

.result-time {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
  flex-shrink: 0;
}

.result-pager {
  flex-shrink: 0;
  padding: 8px 12px 10px;
  display: flex;
  justify-content: center;
  border-top: 1px solid var(--border-color);
}

.trash-actions {
  flex-shrink: 0;
  padding: 8px 12px 10px;
  border-top: 1px solid var(--border-color);
}

.side-loading {
  padding: 16px;
  text-align: center;
}

.side-empty-actions {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 12px;
  padding: 0 12px;
}

.tree-section-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
}

.link-btn {
  border: none;
  background: none;
  color: var(--primary-color);
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}

.explorer-tree {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
  padding: 2px 4px 0 0;
}

.tree-viewport {
  flex: 1;
  min-height: 120px;
  overflow: auto;
  overscroll-behavior: contain;
}

.tree-virtual-space {
  position: relative;
  width: 100%;
}

.tree-virtual-window {
  will-change: transform;
}

.tree-row-wrap {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
  box-sizing: border-box;
}

.tree-row {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  align-items: center;
  gap: 4px;
  border: none;
  background: transparent;
  color: var(--text-primary);
  padding: 0 6px;
  border-radius: 8px;
  cursor: grab;
  text-align: left;
  font-size: 13px;

  &:active {
    cursor: grabbing;
  }

  &:hover {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 14%, transparent);
    color: var(--primary-color);
    font-weight: 600;
  }
}

.tree-twist {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 10px;

  &.spacer {
    visibility: hidden;
  }
}

.tree-icon {
  flex-shrink: 0;
  font-size: 14px;
}

.folder-icon {
  color: #d97706;
}

.note-icon {
  color: var(--text-secondary);
}

.pin-icon {
  color: var(--primary-color);
}

.tree-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-fmt {
  margin-inline-end: 0;
  font-size: 10px;
  line-height: 16px;
  padding: 0 4px;
  flex-shrink: 0;
}

.tree-time {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

.tree-more {
  flex-shrink: 0;
  border: none;
  background: transparent;
  color: var(--text-secondary);
  width: 24px;
  height: 24px;
  border-radius: 6px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  opacity: 0.55;

  &:hover {
    opacity: 1;
    background: var(--surface-2);
  }
}

.tree-meta {
  padding: 6px 12px 4px;
  font-size: 11px;
}

.tree-indent {
  width: 16px;
  flex-shrink: 0;
}

.tree-drop-root {
  margin: 4px 10px 8px;
  padding: 8px 10px;
  border: 1px dashed var(--border-color);
  border-radius: 8px;
  font-size: 12px;
  color: var(--text-secondary);
  text-align: center;
  transition: border-color 0.15s, background 0.15s;

  &.drop-over {
    border-color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 10%, transparent);
    color: var(--primary-color);
  }
}

.tree-row-wrap {
  &.drop-over {
    outline: 1px solid var(--primary-color);
    border-radius: 8px;
    background: color-mix(in srgb, var(--primary-color) 8%, transparent);
  }

  &.dragging {
    opacity: 0.45;
  }
}

.move-tip {
  margin: 0 0 10px;
  font-size: 13px;
}

.move-folder-list {
  max-height: 360px;
  overflow: auto;
  border: 1px solid var(--border-color);
  border-radius: 10px;
  padding: 6px;
}

.move-folder-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  background: transparent;
  text-align: left;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  color: inherit;
  font-size: 13px;

  &:hover:not(:disabled) {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 14%, transparent);
    color: var(--primary-color);
    font-weight: 600;
  }

  &:disabled,
  &.disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.delete-mode-tip {
  margin: 0 0 14px;
  font-size: 13px;
}

/* 三列横向卡片，彻底避免 ant-radio 默认竖排 */
.delete-mode-row {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: stretch;
  gap: 12px;
  width: 100%;
}

.delete-mode-card {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 14px 12px;
  border: 1px solid var(--border-color);
  border-radius: 12px;
  background: var(--surface-2, transparent);
  cursor: pointer;
  text-align: left;
  color: inherit;
  transition: border-color 0.15s, background 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: color-mix(in srgb, var(--primary-color) 50%, var(--border-color));
  }

  &.active {
    border-color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 10%, transparent);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--primary-color) 35%, transparent);
  }

  .dm-title {
    font-weight: 700;
    font-size: 13px;
    color: var(--text-primary);
    line-height: 1.3;
  }

  .dm-desc {
    font-size: 12px;
    line-height: 1.45;
  }
}

.folder-overview {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 28px 32px;
}

.folder-ov-head {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 20px;
  flex-wrap: wrap;

  h2 {
    margin: 0 0 4px;
    font-size: 22px;
    font-weight: 700;
  }

  p {
    margin: 0;
    font-size: 13px;
  }
}

.folder-ov-icon {
  font-size: 28px;
  color: #d97706;
  margin-top: 4px;
}

.folder-ov-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.folder-ov-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 720px;
}

.folder-ov-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  border-radius: 10px;
  padding: 12px 14px;
  cursor: pointer;
  text-align: left;
  color: inherit;
  font-size: 14px;

  span:nth-child(2) {
    flex: 1;
    font-weight: 600;
  }

  &:hover {
    border-color: var(--primary-color);
  }
}

.folder-ov-empty {
  padding: 40px 0;
}

.trash-footer {
  margin-top: auto;
  flex-shrink: 0;
  padding: 10px 8px 12px;
  border-top: 1px solid var(--border-color);
}

.trash-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  border: none;
  border-radius: 10px;
  padding: 10px 12px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  text-align: left;

  &:hover {
    background: var(--surface-2);
    color: var(--text-primary);
  }

  &.active {
    background: color-mix(in srgb, #ef4444 12%, transparent);
    color: #ef4444;
  }
}

.trash-badge {
  margin-left: auto;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  line-height: 20px;
  text-align: center;
  font-weight: 700;
}

.trash-mode-label {
  color: #ef4444;
  font-weight: 600;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 12px 6px;
}

.tags-head {
  margin-top: 8px;
  border-top: 1px solid var(--border-color);
  padding-top: 12px;
}

.side-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.icon-action {
  color: var(--text-secondary);
}

.cat-tree {
  flex: 0 1 auto;
  overflow: auto;
  max-height: 42%;
}

.cat-row {
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 6px;
}

.cat-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: calc(100% - 8px);
  margin: 2px 4px;
  padding: 8px 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  font-size: 13px;

  &.flex-1 {
    flex: 1;
    width: auto;
    margin-right: 0;
  }

  &:hover {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 14%, transparent);
    color: var(--primary-color);
    font-weight: 600;
  }
}

.cat-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-more {
  border: none;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  opacity: 0.5;

  &:hover {
    opacity: 1;
    background: var(--surface-2);
  }
}

.tag-list {
  flex: 1;
  overflow: auto;
  padding: 4px 10px 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-content: flex-start;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  color: var(--text-primary);
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;

  &.active {
    border-color: var(--primary-color);
    color: var(--primary-color);
    background: color-mix(in srgb, var(--primary-color) 12%, transparent);
  }
}

.tag-count {
  opacity: 0.55;
  font-size: 11px;
}

.side-empty {
  font-size: 12px;
  color: var(--text-secondary);
  padding: 8px 4px;
}

.list-toolbar {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-bottom: 1px solid var(--border-color);
}

.search-input {
  flex: 1;
}

.list-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--text-secondary);
}

.list-loading,
.list-empty,
.editor-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--text-secondary);
  padding: 24px;
}

.editor-empty-icon {
  font-size: 36px;
  margin-bottom: 12px;
  opacity: 0.45;
}

.note-list {
  flex: 1;
  overflow: auto;
  padding: 6px 8px 12px;
}

.note-card {
  width: 100%;
  text-align: left;
  border: 1px solid transparent;
  background: transparent;
  border-radius: 12px;
  padding: 10px 12px;
  cursor: pointer;
  margin-bottom: 4px;
  color: inherit;

  &:hover {
    background: var(--surface-2);
  }

  &.active {
    background: color-mix(in srgb, var(--primary-color) 10%, transparent);
    border-color: color-mix(in srgb, var(--primary-color) 35%, transparent);
  }

  &.deleted {
    opacity: 0.65;
  }
}

.note-card-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 4px;
}

.pin-icon,
.pin-on {
  color: var(--primary-color);
}

.fmt-tag {
  font-size: 11px;
  line-height: 18px;
  margin-inline-end: 0;
  flex-shrink: 0;
}

.del-tag {
  margin-left: auto;
  font-size: 11px;
}

.format-badge {
  margin-inline-end: 0;
  user-select: none;

  .fmt-short {
    display: none;
  }
}

.empty-create-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.create-format-tip {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
}

.create-format-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.format-card {
  position: relative;
  text-align: left;
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  border-radius: 12px;
  padding: 14px 14px 16px;
  cursor: pointer;
  color: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;

  &:hover {
    border-color: var(--primary-color);
    box-shadow: 0 0 0 1px color-mix(in srgb, var(--primary-color) 35%, transparent);
  }
}

.format-card-title {
  font-weight: 700;
  font-size: 15px;
  margin-bottom: 8px;
}

.format-card-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

.format-card-tag {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary-color) 16%, transparent);
  color: var(--primary-color);
  font-weight: 600;

  &.recommended {
    /* same */
  }
}

.note-card-snippet {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 2.9em;
}

.note-card-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-secondary);
  align-items: center;
}

.meta-cat {
  background: var(--surface-3);
  padding: 1px 6px;
  border-radius: 4px;
}

.meta-tag {
  opacity: 0.85;
}

.meta-time {
  margin-left: auto;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: var(--text-tertiary, var(--text-secondary));
  opacity: 0.95;
}

.list-pager {
  padding: 8px 12px 12px;
  display: flex;
  justify-content: center;
  border-top: 1px solid var(--border-color);
}

.editor-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 8px;
  border-bottom: 1px solid var(--border-color);
}

.editor-actions {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-shrink: 0;
}

.editor-meta-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  padding: 8px 14px;
  align-items: center;
  border-bottom: 1px solid var(--border-color);
}

.doc-location {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  color: var(--text-secondary);
  max-width: 42%;

  .loc-icon {
    color: #d97706;
    flex-shrink: 0;
  }

  .loc-text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-weight: 600;
    color: var(--text-primary);
  }

  .loc-hint {
    flex-shrink: 0;
    font-size: 11px;
    opacity: 0.75;
  }
}

.doc-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;

  &.disabled {
    opacity: 0.7;
  }
}

.doc-tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: none;
  border-radius: 999px;
  padding: 2px 8px 2px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  color: var(--primary-color);
  background: color-mix(in srgb, var(--primary-color) 12%, transparent);
  transition: background 0.15s, transform 0.1s;

  &:hover:not(:disabled) {
    background: color-mix(in srgb, var(--primary-color) 20%, transparent);
  }

  &:disabled {
    cursor: default;
  }

  .chip-x {
    opacity: 0.55;
    font-size: 14px;
    line-height: 1;
    margin-left: 1px;
  }

  &:hover:not(:disabled) .chip-x {
    opacity: 1;
  }
}

.add-tag-btn {
  border: 1px dashed var(--border-color);
  background: transparent;
  color: var(--text-secondary);
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;

  &:hover:not(:disabled) {
    border-color: var(--primary-color);
    color: var(--primary-color);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.5;
  }
}

.view-mode-group {
  margin-left: auto;
  flex-shrink: 0;
}

.tag-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.tag-picker-list {
  max-height: 200px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.tag-picker-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: none;
  background: transparent;
  text-align: left;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: inherit;

  &:hover {
    background: var(--surface-2, rgba(0, 0, 0, 0.04));
  }

  &.on {
    background: color-mix(in srgb, var(--primary-color) 12%, transparent);
    color: var(--primary-color);
    font-weight: 600;
  }

  .check {
    font-size: 12px;
  }
}

.tag-picker-empty {
  padding: 10px 8px;
  font-size: 12px;
  text-align: center;
}

.tag-picker-create {
  padding: 0;
}

.editor-body {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 0;
  padding: 0 10px 8px;

  &.mode-html {
    grid-template-columns: 1fr;
    min-height: 280px;
  }

  &.mode-edit {
    grid-template-columns: 1fr;
  }

  &.mode-preview {
    grid-template-columns: 1fr;
  }

  &.mode-split {
    grid-template-columns: 1fr 1fr;
  }
}

.html-stack {
  position: relative;
  min-height: 280px;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.html-fast-shell {
  position: absolute;
  inset: 0;
  z-index: 2;
  overflow: auto;
  padding: 14px 16px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 10px;

  &.alone {
    position: relative;
    inset: auto;
    flex: 1;
    min-height: 280px;
  }
}

.shell-loading {
  color: var(--text-secondary);
  padding: 24px 0;
  text-align: center;
}

.shell-body {
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;

  :deep(h1) {
    font-size: 1.85em;
    font-weight: 700;
    line-height: 1.3;
    margin: 0.1em 0 0;
    padding: 0.15em 0 0.55em;
    border: none;
  }

  :deep(h1 + *) {
    margin-top: 0.85em !important;
    padding-top: 0.85em;
    border-top: 1px solid var(--border-color);
  }

  :deep(img) {
    max-width: 100%;
    height: auto;
  }

  :deep(video) {
    max-width: 100%;
  }

  :deep(p) {
    margin: 0.5em 0;
  }
}

/* Markdown：标题区 + 横线 + 正文 */
.md-doc-edit {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 280px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  overflow: hidden;
  transition: box-shadow 0.15s, border-color 0.15s;

  &.md-drag-over {
    border-color: var(--primary-color);
    box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--primary-color) 35%, transparent);
  }
}

.md-doc-title {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-size: 1.65em;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.02em;
  padding: 16px 16px 12px;
  color: var(--text-primary);
}

.md-doc-title::placeholder {
  color: var(--text-secondary);
  opacity: 0.55;
  font-weight: 600;
}

.doc-title-rule {
  height: 0;
  margin: 0 16px;
  border: none;
  border-top: 1px solid var(--border-color);
  flex-shrink: 0;
}

.md-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.md-file-input {
  display: none;
}

.md-toolbar-tip {
  font-size: 12px;
}

.md-body-input {
  flex: 1;
  min-height: 0 !important;
  border: none !important;
  border-radius: 0 !important;
}

/* Markdown 预览：首个 h1 与正文横线分隔 + 可点图片缩放 */
.md-preview.doc-preview,
.md-preview {
  position: relative;

  :deep(h1) {
    font-size: 1.85em;
    font-weight: 700;
    line-height: 1.3;
    margin: 0.1em 0 0;
    padding: 0.15em 0 0.55em;
    border: none;
  }

  :deep(h1 + *) {
    margin-top: 0.85em !important;
    padding-top: 0.85em;
    border-top: 1px solid var(--border-color);
  }

  :deep(img.kb-md-img),
  :deep(img) {
    max-width: 100%;
    height: auto;
    border-radius: 8px;
    margin: 0.5em 0;
    cursor: pointer;
    outline: 2px solid transparent;
    transition: outline-color 0.15s;

    &:hover {
      outline-color: color-mix(in srgb, var(--primary-color) 50%, transparent);
    }
  }

  :deep(table) {
    width: 100%;
    border-collapse: separate;
    border-spacing: 0;
    margin: 1em 0 1.25em;
    font-size: 0.92em;
    border: 1px solid var(--border-color);
    border-radius: 10px;
    overflow: hidden;
  }

  :deep(th),
  :deep(td) {
    padding: 10px 12px;
    border-bottom: 1px solid var(--border-color);
    text-align: left;
    vertical-align: top;
  }

  :deep(th + th),
  :deep(td + td) {
    border-left: 1px solid var(--border-color);
  }

  :deep(th) {
    font-weight: 650;
    background: var(--surface-2);
  }

  :deep(tbody tr:last-child td) {
    border-bottom: none;
  }

  :deep(tbody tr:nth-child(even)) {
    background: color-mix(in srgb, var(--primary-color) 5%, transparent);
  }
}

.md-img-menu {
  position: fixed;
  z-index: 1000;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  background: var(--surface-1);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.12);
}

.md-img-menu-label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-right: 2px;
}

.md-img-menu button {
  border: 1px solid var(--border-color);
  background: var(--surface-2);
  border-radius: 6px;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  color: var(--text-primary);

  &:hover {
    border-color: var(--primary-color);
    color: var(--primary-color);
  }

  &.ghost {
    background: transparent;
    font-weight: 500;
  }
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
}

.doc-title-hint {
  font-size: 13px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42%;
}

.shell-tip {
  position: sticky;
  bottom: 0;
  padding: 8px 0 0;
  font-size: 12px;
  text-align: center;
  background: linear-gradient(transparent, var(--surface-1) 40%);
}

.html-stack > :deep(.rich-wrap) {
  flex: 1;
  min-height: 280px;
  position: relative;
  z-index: 1;
}

.md-input {
  height: 100% !important;
  resize: none;
  border: none !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  background: var(--surface-1);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13.5px;
  line-height: 1.6;
  padding: 14px !important;

  :deep(textarea) {
    height: 100% !important;
    resize: none;
    border: none;
    box-shadow: none !important;
    background: transparent;
    font-family: inherit;
    font-size: inherit;
    line-height: inherit;
  }
}

.md-preview {
  height: 100%;
  overflow: auto;
  padding: 14px 18px;
  border-left: 1px solid var(--border-color);
  font-size: 14px;
  line-height: 1.7;
  color: var(--text-primary);

  :deep(h1),
  :deep(h2),
  :deep(h3) {
    margin: 0.8em 0 0.4em;
    line-height: 1.3;
  }

  :deep(p) {
    margin: 0.5em 0;
  }

  :deep(pre) {
    background: var(--surface-2);
    padding: 10px 12px;
    border-radius: 8px;
    overflow: auto;
  }

  :deep(code) {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    font-size: 0.92em;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 1.4em;
  }

  :deep(blockquote) {
    margin: 0.6em 0;
    padding-left: 12px;
    border-left: 3px solid var(--border-color);
    color: var(--text-secondary);
  }
}

.mode-preview .md-preview {
  border-left: none;
}

.editor-status {
  padding: 6px 14px 10px;
  font-size: 12px;
  color: var(--text-secondary);
  border-top: 1px solid var(--border-color);
  min-height: 28px;
}

@media (max-width: 1100px) {
  .kb-workspace {
    grid-template-columns: minmax(240px, 300px) 1fr;
  }
}

/* —— 小屏：列表 / 详情 全屏切换，桌面双栏完全不变 —— */
.mobile-back-btn {
  display: none;
}

.mobile-more-actions {
  display: none;
}

@media (max-width: 768px) {
  .kb-workspace {
    display: flex;
    flex-direction: column;
    grid-template-columns: none;
    gap: 0;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    border-radius: 14px;
    border: 1px solid var(--border-color);
    background: var(--surface-1);
  }

  .kb-sidebar,
  .kb-editor-pane {
    flex: 1 1 auto;
    min-height: 0;
    border: none;
    border-radius: 0;
    background: transparent;
  }

  /* 默认只看目录列表 */
  .kb-workspace:not(.mobile-detail) .kb-editor-pane {
    display: none;
  }

  /* 打开文档 / 新建 / 夹内概览：只看详情 */
  .kb-workspace.mobile-detail .kb-sidebar {
    display: none;
  }

  .mobile-back-btn {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    flex-shrink: 0;
    height: 30px;
    padding: 0 10px 0 8px;
    border: 1px solid var(--border-color);
    border-radius: 999px;
    background: var(--surface-2);
    color: var(--text-primary);
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;

    &:active {
      background: var(--surface-3);
    }
  }

  .mobile-more-actions {
    display: inline-flex;
  }

  .desktop-only-action {
    display: none !important;
  }

  .desktop-split-mode {
    display: none !important;
  }

  .side-head {
    padding: 12px 14px 8px;
  }

  .side-title {
    font-size: 13px;
    letter-spacing: 0.02em;
    text-transform: none;
    color: var(--text-primary);
    font-weight: 700;
  }

  .side-search {
    padding: 0 12px 10px;
  }

  .tree-drop-root {
    display: none;
  }

  .tree-meta {
    display: none;
  }

  .tree-row {
    font-size: 14px;
  }

  .tree-more {
    opacity: 0.85;
    width: 32px;
    height: 32px;
  }

  .tag-list {
    max-height: 88px;
    padding: 4px 12px 10px;
  }

  .tags-head {
    margin-top: 4px;
  }

  .trash-footer {
    padding: 8px 10px calc(10px + env(safe-area-inset-bottom, 0px));
  }

  .trash-entry {
    padding: 12px 14px;
    border-radius: 12px;
    background: var(--surface-2);
  }

  .result-list {
    padding: 0 10px 12px;
    gap: 8px;
  }

  .result-item {
    padding: 12px 14px;
    border-radius: 12px;
  }

  .result-item-title {
    font-size: 14px;
  }

  .editor-toolbar {
    flex-wrap: nowrap;
    gap: 8px;
    padding: 10px 12px;
    align-items: center;
  }

  .toolbar-left {
    gap: 8px;
    min-width: 0;
  }

  .doc-title-hint {
    max-width: none;
    flex: 1;
    font-size: 14px;
    color: var(--text-primary) !important;
  }

  .format-badge {
    flex-shrink: 0;

    .fmt-full {
      display: none;
    }

    .fmt-short {
      display: inline;
    }
  }

  .editor-actions {
    gap: 6px;
    flex-shrink: 0;
  }

  .mobile-more-btn {
    padding-inline: 10px;
  }

  .editor-meta-row {
    padding: 8px 12px;
    gap: 8px;
    flex-direction: column;
    align-items: stretch;
  }

  .doc-location {
    max-width: none;
    width: 100%;

    .loc-hint {
      display: none;
    }
  }

  .doc-tags {
    width: 100%;
  }

  .view-mode-group {
    margin-left: 0;
    align-self: flex-start;
  }

  .editor-body {
    padding: 0 8px 6px;

    &.mode-html {
      min-height: 0;
    }

    /* 小屏强制单栏，避免残留 split 样式 */
    &.mode-split {
      grid-template-columns: 1fr;
    }
  }

  .md-doc-title {
    font-size: 1.35em;
    padding: 14px 12px 10px;
  }

  .md-toolbar {
    padding: 8px 12px;
    gap: 8px;
  }

  .md-toolbar-tip {
    display: none;
  }

  .md-input {
    padding: 12px !important;
    font-size: 14px;
  }

  .md-preview {
    padding: 12px 14px;
    border-left: none;
  }

  .html-fast-shell {
    padding: 12px;
    border-radius: 8px;
  }

  .folder-overview {
    padding: 12px 14px 20px;
  }

  .folder-back {
    margin-bottom: 12px;
  }

  .folder-ov-head {
    flex-direction: column;
    gap: 12px;
    margin-bottom: 14px;

    h2 {
      font-size: 18px;
    }
  }

  .folder-ov-actions {
    margin-left: 0;
    width: 100%;

    .ant-btn {
      flex: 1;
    }
  }

  .folder-ov-item {
    padding: 14px;
    border-radius: 12px;
  }

  .editor-empty {
    padding: 28px 18px;
    text-align: center;
  }

  .empty-create-actions {
    flex-direction: column;
    width: 100%;
    max-width: 280px;

    .ant-btn {
      width: 100%;
    }
  }

  .editor-status {
    padding: 8px 12px calc(10px + env(safe-area-inset-bottom, 0px));
  }

  .delete-mode-row {
    flex-direction: column;
  }

  /* 附件区：小屏更紧凑 */
  :deep(.file-panel) {
    border-top: 1px solid var(--border-color);
    max-height: 36vh;
    overflow: auto;
  }

  :deep(.file-panel .drop-hint) {
    display: none;
  }
}
</style>
