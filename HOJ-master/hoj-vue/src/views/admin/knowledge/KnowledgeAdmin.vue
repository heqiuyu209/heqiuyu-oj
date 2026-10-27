<template>
  <section class="knowledge-page knowledge-admin">
    <header class="knowledge-heading"><div><h2>知识库资料</h2><p>维护算法讲义和课程资料，完成入库后选择版本发布。</p></div><div v-if="canMaintain" class="knowledge-actions"><el-button v-if="videoConfigured" icon="el-icon-video-camera" :disabled="saving || busy" @click="showVideoImports=true">视频资料</el-button><el-button type="primary" icon="el-icon-plus" :disabled="saving || busy" @click="newDocument">新增资料</el-button></div></header>
    <el-alert v-if="!canMaintain" title="仅站点管理员可以维护知识库资料。" type="info" :closable="false" />
    <template v-else>
      <el-alert v-if="indexConfigured === false" title="资料检索暂未就绪。可以先保存资料，入库完成后再发布。" type="info" :closable="false" show-icon />
      <KnowledgeVideoImports v-if="videoConfigured && showVideoImports" :key="identity+':'+(selectedId || 'new')" :target-id="selectedId" :expected-revision="loadedRevision" :title="form.title" :scope="detail ? detail.visibility : 'ADMIN'" @close="showVideoImports=false" @saved="loadList" @open="openDocument($event.id,$event.version)" />
      <div class="knowledge-admin-layout">
        <aside class="knowledge-surface" aria-label="资料管理列表">
          <form class="knowledge-toolbar" @submit.prevent="page=1;loadList()"><el-input v-model="keyword" placeholder="搜索资料标题" aria-label="搜索管理资料" clearable maxlength="200" /><el-button native-type="submit" icon="el-icon-search" aria-label="搜索资料" /></form>
          <p v-if="listError" class="knowledge-error" role="alert">{{ listError }}<el-button type="text" @click="loadList">重试</el-button></p>
          <p v-if="listLoading" class="knowledge-muted" role="status"><i class="el-icon-loading" /> 正在读取资料</p>
          <div v-else-if="!items.length && !listError" class="knowledge-empty"><i class="el-icon-folder-opened" /><h3>{{ keyword ? '没有匹配的资料' : '添加第一份讲义' }}</h3><p>可粘贴正文，或导入 Markdown、TXT、PDF 文件。</p></div>
          <div class="knowledge-admin-list"><button v-for="item in items" :key="item.id" type="button" :class="['knowledge-admin-row', { selected: selectedId === item.id }]" :aria-current="selectedId === item.id ? 'true' : null" :disabled="saving || busy" @click="openDocument(item.id)"><strong>{{ item.title }}</strong><span>{{ item.visibility === 'PUBLIC' ? '全站可见' : '仅管理员' }}<span>{{ item.publishedVersion ? '已发布 v'+item.publishedVersion : '未发布' }}</span></span><small>最新版本 {{ item.latestVersion }}</small></button></div>
          <el-pagination v-if="total > 20" small layout="prev, pager, next" :total="total" :page-size="20" :current-page="page" @current-change="page=$event;loadList()" />
          <el-button type="text" icon="el-icon-refresh" :disabled="listLoading" @click="loadList">刷新列表</el-button>
        </aside>
        <section ref="editor" class="knowledge-surface knowledge-admin-editor" aria-label="资料编辑">
          <div v-if="!formOpen && !detailLoading" class="knowledge-empty"><i class="el-icon-document" /><h3>从一份资料开始</h3><p>选择左侧资料查看版本，或新增资料。</p></div>
          <div v-if="detailLoading" class="knowledge-empty" role="status"><i class="el-icon-loading" />正在读取资料版本</div>
          <p v-if="error" class="knowledge-error" role="alert">{{ error }}</p>
          <template v-if="formOpen && !detailLoading">
            <div class="knowledge-editor-heading"><h3>{{ selectedId ? '编辑资料' : '新增资料' }}</h3><el-button type="text" :disabled="saving || busy" @click="closeEditor">收起编辑</el-button></div>
            <el-alert v-if="conflict" title="资料已被更新。先重新载入，再保存或调整发布状态。" type="warning" :closable="false"><el-button type="text" @click="openDocument(selectedId)">重新载入</el-button></el-alert>
            <template v-if="detail">
              <div class="knowledge-toolbar knowledge-version"><label for="knowledge-version">查看版本</label><el-select id="knowledge-version" :value="selectedVersion" :disabled="saving || busy" aria-label="资料版本" @change="selectVersion"><el-option v-for="version in detail.versions" :key="version.version" :value="version.version" :label="'v'+version.version+' · '+stateLabel(version)" /></el-select><span class="knowledge-muted">{{ detail.publishedVersion ? '当前发布 v'+detail.publishedVersion : '当前未发布' }}</span></div>
              <div class="knowledge-meta-panel"><label>可见范围</label><el-select :value="detail.visibility" :disabled="saving || busy || dirty || conflict" aria-label="已保存资料可见范围" @change="changeVisibility"><el-option label="仅管理员" value="ADMIN" /><el-option label="全站可见" value="PUBLIC" /></el-select><small>调整范围会立即影响已发布资料。</small></div>
              <p v-if="selectedState" class="knowledge-muted" role="status">{{ stateDescription(selectedState) }}</p>
            </template>
            <el-form label-position="top" @submit.native.prevent="save">
              <el-form-item label="资料标题"><el-input v-model="form.title" :maxlength="200" :disabled="saving || busy || conflict" placeholder="例如：前缀和与差分讲义" aria-label="资料标题" /></el-form-item>
              <div class="knowledge-editor-options"><el-form-item label="正文格式"><el-select v-model="form.format" :disabled="saving || busy || conflict || fileLoading || ['pdf', 'video'].includes(form.format)" aria-label="资料格式"><el-option label="Markdown" value="markdown" /><el-option label="纯文本" value="text" /><el-option label="PDF 提取文字" value="pdf" disabled /><el-option label="视频字幕" value="video" disabled /></el-select></el-form-item><el-form-item v-if="!selectedId" label="可见范围"><el-select v-model="form.visibility" :disabled="saving || fileLoading" aria-label="新资料可见范围"><el-option label="仅管理员" value="ADMIN" /><el-option label="全站可见" value="PUBLIC" /></el-select></el-form-item><div><input ref="file" type="file" accept=".md,.markdown,.txt,.pdf" class="knowledge-file" aria-label="导入资料文件" @change="importFile" /><el-button icon="el-icon-upload2" :disabled="saving || busy || conflict || fileLoading" @click="$refs.file.click()">导入 Markdown / TXT / PDF</el-button></div></div>
              <div v-if="form.format === 'pdf'" class="knowledge-source-panel" role="status"><strong><i class="el-icon-document" /> {{ pdfFile ? pdfFile.name : sourceInfo && sourceInfo.name }}</strong><p v-if="pdfFile">已选择 {{ (pdfFile.size / 1024).toFixed(1) }} KiB，保存后提取文字并入库。</p><p v-else-if="sourceInfo">共 {{ sourceInfo.pages }} 页 · {{ sourceInfo.extractionMode === 'OCR_REVIEWED' ? '本地 OCR · 管理员校订' : sourceInfo.extractionMode === 'OCR' ? '本地 OCR 识别' : sourceInfo.extractionMode === 'NATIVE_REVIEWED' ? '原有文字 · 管理员校订' : '原有文字提取' }}<span v-if="sourceInfo.emptyPages.length">，第 {{ sourceInfo.emptyPages.join('、') }} 页未提取到文字，请核对原件</span>。原件随版本保留。</p><el-checkbox v-if="pdfFile" v-model="form.ocr" :disabled="saving || busy || fileLoading" aria-label="启用本地 OCR">扫描件或图文混排：启用本地 OCR</el-checkbox><p v-if="pdfFile && form.ocr" class="knowledge-muted">逐页识别约需数十秒；公式、代码和识别为空的页面请对照原件检查，确认后再发布。识别超时请拆分文件重试。</p><small>PDF 最多 32 MiB、200 页、30 万字；提取在本地完成。发布前请对照原件核对公式与代码。整本教材首次入库可能需要数分钟，可稍后回来查看状态。</small><el-button v-if="sourceInfo && !pdfFile" type="text" icon="el-icon-download" :loading="downloading" :disabled="downloading || saving || busy" @click="downloadOriginal">下载此版本原件</el-button></div>
              <div v-if="canReviewPdf" class="knowledge-ocr-review">
                <h4>按页校订 PDF 文字</h4><p class="knowledge-muted">对照原件修正错字、公式与代码，保存后生成新版本，再选择版本发布。分页会自动保留。{{ contentLength.toLocaleString() }} / {{ contentLimit.toLocaleString() }} 字</p>
                <el-form-item v-for="(text, index) in pdfPages" :key="index" :label="'第 '+(index+1)+' 页'"><el-input :value="text" type="textarea" :rows="5" :disabled="saving || busy || conflict || fileLoading" :maxlength="contentLimit * 2" :aria-label="'校订第 '+(index+1)+' 页'" @input="updatePdfPage(index, $event)" /></el-form-item>
              </div>
              <el-form-item v-else-if="form.format !== 'video'" :label="form.format === 'pdf' ? '提取文字（只读）' : '资料正文'"><el-input v-model="form.content" type="textarea" :rows="16" :readonly="form.format === 'pdf'" :disabled="saving || busy || conflict || fileLoading" :maxlength="contentLimit * 2" :placeholder="pdfFile ? '保存文件后显示按页提取的文字' : '在此粘贴或导入讲义正文'" aria-label="资料正文" /><small class="knowledge-muted">{{ contentLength.toLocaleString() }} / {{ contentLimit.toLocaleString() }} 字。上传后先入库，发布前学生看不到新版本。</small></el-form-item>
              <KnowledgeVideo v-if="form.format === 'video' && sourceInfo" :key="identity+':'+selectedId+':'+selectedVersion" :resource="{ id: selectedId, version: selectedVersion, maintenance: true }" :content="form.content" :source="sourceInfo" :editable="!saving && !busy && !conflict" :allowed="canMaintain" @input="form.content=$event;form.reviewedVideo=true;error=''" />
              <div class="knowledge-actions knowledge-editor-save"><el-button native-type="submit" type="primary" :loading="saving" :disabled="saving || busy || fileLoading || conflict || !form.title.trim() || !hasBody || selectedId && !dirty">{{ selectedId ? '保存新版本并入库' : '保存资料并入库' }}</el-button><small v-if="dirty" class="knowledge-muted">有未保存的修改</small></div>
            </el-form>
            <div v-if="detail" class="knowledge-publication"><h3>发布与维护</h3><p class="knowledge-muted">发布的是已保存版本。新版本入库失败时，原已发布版本仍可阅读。</p><div class="knowledge-actions"><el-button type="primary" :disabled="busy || saving || dirty || conflict || !selectedState || selectedState.state !== 'INDEXED' || detail.publishedVersion === selectedVersion" @click="command('publish', {version:selectedVersion}, '发布选中的版本？')">发布 v{{ selectedVersion }}</el-button><el-button :disabled="busy || saving || dirty || conflict || !detail.publishedVersion" @click="command('unpublish', {}, '撤回后，用户将无法读取这份资料及其引用。继续撤回？')">撤回发布</el-button><el-button :disabled="busy || saving || dirty || conflict || !selectedState || selectedState.jobState === 'RUNNING' || selectedState.jobState === 'QUEUED'" @click="command('versions/'+selectedVersion+'/retry', {}, '重新入库这个版本？已发布资料保留当前发布状态。')">{{ selectedState && selectedState.state === 'INDEXED' ? '重建索引' : '重试入库' }}</el-button><el-button type="text" class="knowledge-delete" :disabled="busy || saving || dirty || conflict" @click="command('delete', {}, '删除后将撤回整份资料，并清理所有版本索引。继续删除？')">删除资料</el-button></div></div>
          </template>
        </section>
      </div>
    </template>
  </section>
</template>
<script>
import api from '@/common/api';
import authoringGuard, { confirmAuthoringDiscard } from '@/common/authoringGuard';
import { requestId, responseData, errorMessage } from '@/common/objective';
import { knowledgePdfBlob, saveKnowledgePdf } from '@/common/knowledgeDownload';
import { videoTranscript } from '@/common/knowledgeVideo';
import KnowledgeVideo from '@/components/oj/learning/KnowledgeVideo';
import KnowledgeVideoImports from '@/components/admin/KnowledgeVideoImports';
import '@/styles/knowledge.css';
const empty = () => ({ title: '', content: '', format: 'markdown', visibility: 'ADMIN', ocr: false, reviewedOcr: false, reviewedPdf: false });
export default {
  name: 'KnowledgeAdmin', mixins: [authoringGuard], components: { KnowledgeVideo, KnowledgeVideoImports },
  data: () => ({ videoConfigured: false, showVideoImports: false, items: [], total: 0, page: 1, keyword: '', listLoading: false, listError: '', indexConfigured: null, listRequest: 0, detailRequest: 0, selectedId: null, selectedVersion: null, detail: null, loadedRevision: null, detailLoading: false, formOpen: false, form: empty(), nonce: '', saving: false, busy: false, fileLoading: false, error: '', editorEpoch: 0, pdfFile: null, attachmentId: '', sourceInfo: null, downloading: false }),
  computed: {
    identity() { return [this.$store.getters.userInfo.uid, this.$store.getters.authEpoch, (this.$store.getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    canMaintain() { return (this.$store.getters.userInfo.roleList || []).some(role => role === 'root' || role === 'admin'); },
    authoringState() { return this.formOpen && !this.detailLoading ? { ...this.form, attachmentId: this.attachmentId } : null; },
    authoringDiscardMessage() { return '尚未保存的资料内容将丢失，已保存的版本会保留。是否放弃本次编辑？'; },
    dirty() { return this.hasUnsavedAuthoring(); },
    conflict() { return this.detail && this.detail.revision !== this.loadedRevision; },
    selectedState() { return this.detail && this.detail.versions.find(version => version.version === this.selectedVersion); },
    contentLength() { return Array.from(this.form.content).length; },
    contentLimit() { return this.form.format === 'pdf' ? 300000 : 200000; },
    canReviewPdf() { return this.form.format === 'pdf' && !this.pdfFile && this.sourceInfo && ['NATIVE', 'NATIVE_REVIEWED', 'OCR', 'OCR_REVIEWED'].includes(this.sourceInfo.extractionMode); },
    pdfPages() { return this.canReviewPdf ? this.form.content.split('\n\f\n') : []; },
    hasBody() { return ['pdf', 'video'].includes(this.form.format) ? !!(this.pdfFile || this.sourceInfo && this.form.sourceVersion) : !!this.form.content.trim(); },
  },
  watch: { identity() { this.showVideoImports = false; this.videoConfigured = false; this.resetEditor(); this.items = []; this.total = 0; this.listRequest++; this.listLoading = false; this.listError = ''; if (this.canMaintain) this.loadList(); } },
  mounted() { if (this.canMaintain) this.loadList(); this.poll = setInterval(() => { if (!document.hidden && this.canMaintain && this.selectedId && !this.saving && !this.busy && !this.detailLoading) this.refreshDetail(); }, 5000); },
  beforeDestroy() { this.editorEpoch++; this.listRequest++; this.detailRequest++; clearInterval(this.poll); },
  methods: {
    updatePdfPage(index, text) { if (!this.canReviewPdf || !this.canMaintain || this.saving || this.busy || this.conflict) return; if (text.includes('\f')) { this.error = '单页校订不能包含换页符。'; return; } const pages = this.pdfPages.slice(); pages[index] = text; this.form.content = pages.join('\n\f\n'); this.form.reviewedPdf = true; this.error = ''; },
    current(identity, epoch) { return this.canMaintain && identity === this.identity && (epoch == null || epoch === this.editorEpoch); },
    resetEditor() { this.editorEpoch++; this.detailRequest++; this.formOpen = false; this.selectedId = null; this.selectedVersion = null; this.detail = null; this.loadedRevision = null; this.detailLoading = false; this.form = empty(); this.nonce = ''; this.saving = false; this.busy = false; this.fileLoading = false; this.error = ''; this.pdfFile = null; this.attachmentId = ''; this.sourceInfo = null; this.downloading = false; this.markAuthoringClean(); },
    async loadList() { if (!this.canMaintain) return; const identity = this.identity, request = ++this.listRequest; this.listLoading = true; this.listError = ''; try { const data = responseData(await api.knowledgeAdminList({ page: this.page, keyword: this.keyword })); if (!this.current(identity) || request !== this.listRequest) return; this.items = data.items; this.total = data.total; this.indexConfigured = data.indexConfigured; this.videoConfigured = data.videoConfigured === true; } catch (error) { if (this.current(identity) && request === this.listRequest) this.listError = errorMessage(error); } finally { if (this.current(identity) && request === this.listRequest) this.listLoading = false; } },
    async newDocument() { const identity = this.identity; if (!await confirmAuthoringDiscard(this) || !this.current(identity)) return; this.resetEditor(); this.formOpen = true; this.nonce = requestId(); this.markAuthoringClean(); },
    async closeEditor() { const identity = this.identity; if (await confirmAuthoringDiscard(this) && this.current(identity)) this.resetEditor(); },
    async openDocument(id, version) { const identity = this.identity; if (!await confirmAuthoringDiscard(this) || !this.current(identity)) return; this.resetEditor(); this.selectedId = id; this.formOpen = true; this.detailLoading = true; const epoch = this.editorEpoch, request = ++this.detailRequest; try { const data = responseData(await api.knowledgeAdminDetail(id)); if (!this.current(identity, epoch) || request !== this.detailRequest) return; this.detail = data; this.loadedRevision = data.revision; this.indexConfigured = data.indexConfigured; await this.loadVersion(version || data.latestVersion, identity, epoch); } catch (error) { if (this.current(identity, epoch)) this.error = errorMessage(error); } finally { if (this.current(identity, epoch)) this.detailLoading = false; this.$nextTick(() => { if (this.current(identity, epoch)) this.markAuthoringClean(); }); } },
    async loadVersion(version, identity, epoch) { const data = responseData(await api.knowledgeAdminVersion(this.selectedId, version)); if (!this.current(identity, epoch)) return; this.selectedVersion = version; this.form = { title: data.title, format: data.format, content: data.content, visibility: this.detail.visibility, ocr: !!(data.source && ['OCR', 'OCR_REVIEWED'].includes(data.source.extractionMode)), reviewedOcr: false, reviewedPdf: false, reviewedVideo: false, ...(['pdf', 'video'].includes(data.format) ? { sourceVersion: version } : {}) }; this.pdfFile = null; this.attachmentId = ''; this.sourceInfo = data.source || null; this.nonce = requestId(); this.error = ''; },
    async selectVersion(version) { const identity = this.identity; if (!await confirmAuthoringDiscard(this) || !this.current(identity)) return; this.editorEpoch++; const epoch = this.editorEpoch; this.detailRequest++; this.detailLoading = true; try { await this.loadVersion(version, identity, epoch); } catch (error) { if (this.current(identity, epoch)) this.error = errorMessage(error); } finally { if (this.current(identity, epoch)) { this.detailLoading = false; this.markAuthoringClean(); } } },
    async refreshDetail() { const identity = this.identity, epoch = this.editorEpoch, id = this.selectedId, request = ++this.detailRequest; try { const data = responseData(await api.knowledgeAdminDetail(id)); if (this.current(identity, epoch) && request === this.detailRequest) { this.detail = data; this.indexConfigured = data.indexConfigured; } } catch (error) { if (this.current(identity, epoch) && request === this.detailRequest) this.error = errorMessage(error); } },
    stateLabel(version) { if (version.jobState === 'RUNNING') return '正在入库'; if (version.jobState === 'QUEUED') return version.attempts ? '等待重试' : '等待入库'; return { INDEXED: '已入库', FAILED: '入库失败', DELETED: '已删除', QUEUED: '等待入库' }[version.state] || version.state; },
    stateDescription(version) { const state = this.stateLabel(version); if (version.state === 'INDEXED' && version.jobState === 'DONE') return '已完成入库，可发布这个版本。'; if (version.lastError === 'INVALID_CONTENT') return '资料无法入库，请检查正文格式或减少内容，再保存新版本。'; if (version.jobState === 'FAILED') return '入库失败，已有发布版本保留。可点击“重试入库”。'; return state + (version.attempts ? '，已尝试 ' + version.attempts + ' 次。' : '。'); },
    async importFile(event) {
      const file = event.target.files[0]; event.target.value = '';
      if (!file || this.saving || this.busy || this.fileLoading || this.conflict) return;
      const isPdf = /\.pdf$/i.test(file.name);
      if (!/\.(md|markdown|txt|pdf)$/i.test(file.name) || file.size > (isPdf ? 33554432 : 1048576)) { this.error = '请选择 32 MiB 以内的 PDF，或 1 MiB 以内的 Markdown / TXT 文件。'; return; }
      const identity = this.identity, epoch = this.editorEpoch; this.fileLoading = true;
      try {
        const text = isPdf ? '' : new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
        if (!this.current(identity, epoch)) return;
        if (Array.from(text).length > 200000 || text.includes('\0')) throw Error('invalid text');
        if (this.form.content.trim() || this.pdfFile || this.sourceInfo) await this.$confirm('导入文件将替换当前正文；已保存版本仍会保留。继续导入？', '替换正文', { confirmButtonText: '导入', cancelButtonText: '取消' });
        if (!this.current(identity, epoch)) return;
        this.pdfFile = isPdf ? file : null; this.attachmentId = isPdf ? requestId() : ''; this.sourceInfo = null;
        this.form = { ...this.form, content: text, format: isPdf ? 'pdf' : /\.txt$/i.test(file.name) ? 'text' : 'markdown', ocr: false, reviewedOcr: false, reviewedPdf: false }; this.$delete(this.form, 'sourceVersion');
        if (!this.form.title.trim()) this.form.title = Array.from(file.name.replace(/\.(md|markdown|txt|pdf)$/i, '')).slice(0, 200).join('');
        this.error = '';
      } catch (error) { if (this.current(identity, epoch) && error !== 'cancel' && error !== 'close') this.error = '无法读取文件，请使用 UTF-8 文本，正文最多 20 万字。'; }
      finally { if (this.current(identity, epoch)) this.fileLoading = false; }
    },
    async save() {
      if (this.saving || this.busy || this.fileLoading || this.conflict || !this.canMaintain || !this.form.title.trim() || !this.hasBody) return;
      if (this.form.format === 'video') { try { videoTranscript(this.form.content); } catch (error) { this.error = error.message; return; } }
      if (this.contentLength > this.contentLimit || this.form.content.includes('\0')) { this.error = (this.form.format === 'pdf' ? 'PDF 文字最多 30 万字' : '正文最多 20 万字') + '，不能包含 NUL 字符。'; return; }
      const identity = this.identity, epoch = this.editorEpoch; this.detailRequest++; this.saving = true; this.error = '';
      const metadata = { title: this.form.title, visibility: this.detail ? this.detail.visibility : this.form.visibility, requestId: this.nonce, expectedRevision: this.loadedRevision };
      try {
        const response = this.pdfFile ? await api.knowledgeUploadPdf(this.selectedId, this.pdfFile, { ...metadata, fileName: this.pdfFile.name, ocr: this.form.ocr })
          : await api.knowledgeUpload(this.selectedId, { ...this.form, ...metadata, content: this.form.format === 'pdf' && !this.form.reviewedPdf || this.form.format === 'video' && !this.form.reviewedVideo ? undefined : this.form.content });
        const data = responseData(response); if (!this.current(identity, epoch)) return;
        this.selectedId = data.id; this.detail = data; this.loadedRevision = data.revision; this.indexConfigured = data.indexConfigured;
        await this.loadVersion(data.requestedVersion || data.latestVersion, identity, epoch); if (!this.current(identity, epoch)) return;
        this.markAuthoringClean(); this.$message.success('资料已保存，等待入库。'); this.loadList();
      } catch (error) { if (this.current(identity, epoch)) this.error = errorMessage(error); }
      finally { if (this.current(identity, epoch)) this.saving = false; }
    },
    async downloadOriginal() {
      if (!this.canMaintain || !this.sourceInfo || this.pdfFile || this.downloading) return;
      const identity = this.identity, epoch = this.editorEpoch, name = this.sourceInfo.name;
      this.downloading = true; this.error = '';
      try { const blob = await knowledgePdfBlob(await api.knowledgeOriginal(this.selectedId, this.selectedVersion, true)); if (this.current(identity, epoch)) saveKnowledgePdf(blob, name); }
      catch (error) { if (this.current(identity, epoch)) this.error = errorMessage(error); }
      finally { if (this.current(identity, epoch)) this.downloading = false; }
    },
    async changeVisibility(visibility) { await this.command('visibility', { visibility }, '调整可见范围会立即影响已发布资料，继续调整？'); },
    async command(action, extra, message) { if (this.saving || this.busy || this.dirty || this.conflict || !this.canMaintain) return; const identity = this.identity, epoch = this.editorEpoch; try { await this.$confirm(message, '资料维护', { confirmButtonText: '继续', cancelButtonText: '取消', type: 'warning' }); if (!this.current(identity, epoch)) return; this.detailRequest++; this.busy = true; const data = responseData(await api.knowledgeChange(this.selectedId, action, { expectedRevision: this.loadedRevision, ...extra })); if (!this.current(identity, epoch)) return; if (action === 'delete') this.resetEditor(); else { this.detail = data; this.loadedRevision = data.revision; this.form.visibility = data.visibility; this.markAuthoringClean(); } this.$message.success(action === 'publish' ? '版本已发布。' : action === 'unpublish' ? '已撤回发布。' : action === 'delete' ? '资料已删除。' : action === 'visibility' ? '可见范围已更新。' : '已重新排队入库。'); this.loadList(); } catch (error) { if (this.current(identity, epoch) && error !== 'cancel' && error !== 'close') this.error = errorMessage(error); } finally { if (this.current(identity, epoch)) this.busy = false; } },
  },
};
</script>
<style scoped>
.knowledge-admin { max-width: 1540px; }
.knowledge-admin-layout { display: grid; grid-template-columns: minmax(250px, 330px) minmax(0, 1fr); gap: 24px; align-items: start; }
.knowledge-admin .knowledge-toolbar { flex-wrap: nowrap; gap: 8px; }
.knowledge-admin-row { width: 100%; text-align: left; border: 0; border-bottom: 1px solid var(--line); background: transparent; padding: 18px 10px; color: var(--text); cursor: pointer; }
.knowledge-admin-row.selected { background: var(--soft); box-shadow: inset 3px 0 var(--accent); }
.knowledge-admin-row strong { display: block; line-height: 1.7; overflow-wrap: anywhere; }
.knowledge-admin-row > span { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 6px; font-size: 13px; margin-top: 8px; }
.knowledge-admin-row small { display: block; color: var(--muted); margin-top: 8px; }
.knowledge-admin-editor { scroll-margin-top: 100px; }
.knowledge-editor-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
.knowledge-editor-heading h3, .knowledge-publication h3 { margin: 0; font-size: 20px; }
.knowledge-version .el-select { min-width: 150px; max-width: 230px; }
.knowledge-admin .knowledge-version { flex-wrap: wrap; }
.knowledge-version label { white-space: nowrap; }
.knowledge-meta-panel { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 16px; }
.knowledge-meta-panel .el-select { max-width: 155px; }
.knowledge-meta-panel small { color: var(--muted); line-height: 1.7; }
.knowledge-editor-options { display: flex; flex-wrap: wrap; align-items: center; gap: 18px; }
.knowledge-editor-options .el-select { width: 170px; }
.knowledge-file { display: none; }
.knowledge-source-panel { padding: 18px; margin-bottom: 22px; border: 1px solid var(--line); background: var(--soft); border-radius: 10px; line-height: 1.8; overflow-wrap: anywhere; }
.knowledge-source-panel small { display: block; color: var(--muted); }
.knowledge-admin /deep/ .el-textarea__inner { font-family: Consolas, 'Microsoft YaHei', monospace; line-height: 1.85; }
.knowledge-editor-save { margin: 22px 0; }
.knowledge-publication { margin-top: 24px; padding-top: 24px; border-top: 1px solid var(--line); }
.knowledge-publication .knowledge-actions { margin-top: 18px; }
.knowledge-delete { color: var(--knowledge-danger); }
@media (max-width: 980px) { .knowledge-admin-layout { grid-template-columns: 1fr; } .knowledge-admin-list { max-height: 300px; overflow: auto; } }
</style>
