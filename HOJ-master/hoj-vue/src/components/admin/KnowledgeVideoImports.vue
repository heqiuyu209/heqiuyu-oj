<template>
  <section class="knowledge-surface video-imports" aria-label="视频资料导入">
    <header class="video-import-heading"><div><h3>视频资料</h3><p class="knowledge-muted">上传后在本地转写，审阅字幕、保存入库，再到资料版本中发布。</p></div><el-button type="text" :disabled="saving" @click="close">收起</el-button></header>
    <el-alert v-if="configured === false" title="视频处理服务尚未就绪，已有转写草稿会保留。" type="info" :closable="false" />
    <p v-if="error" class="knowledge-error" role="alert">{{ error }}</p>
    <div class="video-import-layout">
      <aside>
        <form class="video-upload" @submit.prevent="upload">
          <h4>{{ targetId ? '为当前资料上传新视频' : '添加课程视频' }}</h4>
          <el-input v-model="uploadTitle" aria-label="视频资料标题" placeholder="课程或讲义标题" maxlength="200" :disabled="saving" />
          <div class="knowledge-toolbar"><el-select v-model="visibility" aria-label="视频可见范围" :disabled="saving || !!targetId"><el-option label="仅管理员" value="ADMIN" /><el-option label="全站可见" value="PUBLIC" /></el-select><el-select v-model="language" aria-label="视频讲解语言" :disabled="saving"><el-option label="自动识别语言" value="auto" /><el-option label="中文" value="zh" /><el-option label="英语" value="en" /></el-select></div>
          <input ref="file" type="file" accept=".mp4,.webm" :disabled="saving" aria-label="选择课程视频" @change="chooseFile" />
          <p class="knowledge-muted">MP4 / WebM，最多 1 GiB、2 小时。语音在本地识别；画面文字不参与检索。</p>
          <el-progress v-if="uploading" :percentage="uploadProgress" :stroke-width="7" />
          <el-button native-type="submit" type="primary" icon="el-icon-upload2" :loading="uploading" :disabled="saving || !file || !uploadTitle.trim() || configured === false">{{ uploading ? '正在上传原件' : '上传并开始转写' }}</el-button>
        </form>
        <div class="video-task-heading"><h4>转写任务</h4><el-button type="text" icon="el-icon-refresh" :disabled="refreshing" @click="refresh">刷新</el-button></div>
        <p v-if="!tasks.length" class="knowledge-muted">还没有转写任务。离开页面后，已上传的任务会继续处理。</p>
        <button v-for="task in tasks" :key="task.id" type="button" :class="['video-task', {selected: selected && selected.id === task.id}]" :disabled="saving" @click="select(task.id)"><strong>{{ task.title }}</strong><span>{{ stateLabel(task) }}</span><small v-if="task.durationMs">{{ clock(task.processedMs || 0) }} / {{ clock(task.durationMs) }}</small></button>
        <small class="knowledge-muted">最多保留 12 份未保存草稿，每位管理员 4 份。保存或取消后释放名额。</small>
      </aside>
      <div class="video-review">
        <div v-if="!selected" class="knowledge-empty"><i class="el-icon-video-camera" /><h3>选择一个转写任务</h3><p>完成后可对照原视频校订字幕。</p></div>
        <template v-else>
          <h4>{{ selected.title }}</h4><p class="knowledge-muted" role="status">{{ selected.sourceName }} · {{ stateLabel(selected) }}</p>
          <el-progress v-if="['QUEUED','RUNNING'].includes(selected.state) && selected.durationMs" :percentage="Math.floor((selected.processedMs || 0) / selected.durationMs * 100)" />
          <p v-if="selected.state === 'FAILED'" class="knowledge-error">{{ failureLabel(selected.lastError) }}</p>
          <div v-if="selected.state === 'READY'" class="video-review-form">
            <label>保存资料标题<el-input v-model="draftTitle" aria-label="审阅后资料标题" maxlength="200" :disabled="saving" /></label>
            <el-checkbox v-if="selected.targetDocumentId" v-model="asNew" :disabled="saving">另存为独立资料，保留原资料</el-checkbox>
            <div class="knowledge-actions video-review-save"><el-button type="primary" :loading="saving && !uploading" :disabled="saving || !draftTitle.trim()" @click="save">保存资料并入库</el-button><small class="knowledge-muted">{{ reviewed ? '将保存管理员校订版，原始识别保留。' : '将保存原始识别字幕。' }}保存后还需显式发布。</small></div>
            <KnowledgeVideo :key="selected.id" :resource="{ id: selected.id, draft: true }" :content="captions" :source="{ name: selected.sourceName, extractionMode: 'ASR' }" :editable="!saving" :allowed="canMaintain" @input="captions=$event;reviewed=true;error=''" />
          </div>
          <div class="knowledge-actions video-task-actions">
            <template v-if="selected.state === 'FAILED'"><el-button :disabled="saving" @click="retry(false)">从检查点重试</el-button><el-button :disabled="saving" @click="retry(true)">重新转写整个视频</el-button></template>
            <el-button v-if="selected.state === 'SAVED'" type="primary" @click="$emit('open', {id:selected.documentId,version:selected.version})">查看已保存资料</el-button>
            <el-button v-else type="text" :disabled="saving" @click="cancel">取消任务并清理原件</el-button>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>
<script>
import axios from 'axios';
import api from '@/common/api';
import authoringGuard, { confirmAuthoringDiscard } from '@/common/authoringGuard';
import { requestId, responseData, errorMessage } from '@/common/objective';
import { videoTranscript, videoClock } from '@/common/knowledgeVideo';
import KnowledgeVideo from '@/components/oj/learning/KnowledgeVideo';
export default {
  name: 'KnowledgeVideoImports', mixins: [authoringGuard], components: { KnowledgeVideo },
  props: { targetId: { type: String, default: null }, expectedRevision: { type: Number, default: null }, title: { type: String, default: '' }, scope: { type: String, default: 'ADMIN' } },
  data() { return { uploadTitle: this.title, visibility: this.scope, language: 'auto', file: null, fileIdentity: '', nonce: requestId(), tasks: [], configured: null, selected: null, captions: '', draftTitle: '', reviewed: false, asNew: false, saveNonce: '', saving: false, uploading: false, uploadProgress: 0, refreshing: false, error: '', generation: 0, listRequest: 0, detailRequest: 0 }; },
  computed: {
    identity() { const getters = this.$store.getters; return [getters.userInfo.uid, getters.authEpoch, (getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    canMaintain() { return (this.$store.getters.userInfo.roleList || []).some(role => ['root','admin'].includes(role)); },
    authoringState() { return this.file || this.selected && this.selected.state === 'READY' ? { fileIdentity: this.fileIdentity, uploadTitle: this.uploadTitle, visibility: this.visibility, language: this.language, captions: this.captions, title: this.draftTitle, asNew: this.asNew } : null; },
    authoringDiscardMessage() { return '未上传的视频和未保存的字幕修改将丢失，服务端转写任务会保留。是否放弃本次编辑？'; },
  },
  watch: { scope(value) { if (this.targetId) this.visibility = value; }, identity() { this.generation++; this.listRequest++; this.detailRequest++; if (this.uploadCancellation) this.uploadCancellation.cancel(); this.file = null; this.fileIdentity = ''; this.selected = null; this.captions = ''; this.tasks = []; this.error = ''; this.saving = false; this.uploading = false; this.refreshing = false; this.markAuthoringClean(); if (this.canMaintain) this.refresh(); } },
  mounted() { this.markAuthoringClean(); this.refresh(); this.timer = setInterval(() => { if (!document.hidden && !this.saving) this.refresh(); }, 5000); },
  beforeDestroy() { this.generation++; clearInterval(this.timer); if (this.uploadCancellation) this.uploadCancellation.cancel(); },
  methods: {
    hasUnsavedAuthoring() { return !!this.file || authoringGuard.methods.hasUnsavedAuthoring.call(this); },
    clock: videoClock,
    current(identity, generation) { return this.canMaintain && identity === this.identity && generation === this.generation && !this._isBeingDestroyed; },
    stateLabel(task) { return { QUEUED: task.stage === 'PROBE' ? '等待读取音轨' : '等待继续转写', RUNNING: task.stage === 'PROBE' ? '正在读取音轨' : '正在转写', READY: '转写完成，待审阅保存', FAILED: '转写失败，草稿保留', SAVED: '已保存到资料版本' }[task.state] || task.state; },
    failureLabel(code) { return { ACCESS_REVOKED: '原处理账号已失去管理权限，可由当前管理员重试。', VIDEO_MODEL_CHANGED: '转写模型已变化，请重新转写整个视频。', VIDEO_NO_SPEECH: '没有识别到语音，请检查音轨或重新上传。', VIDEO_TRANSCRIPT_LIMIT: '字幕超过限制，请拆分视频。', VIDEO_DURATION_LIMIT: '视频超过 2 小时，请拆分。', VIDEO_UNSUPPORTED_CODEC: '请转换为 H.264 / AAC MP4 或常见 WebM 后重新上传。', VIDEO_NO_AUDIO: '视频没有可用音轨，请重新上传包含讲解的视频。', VIDEO_SOURCE_CHANGED: '视频原件已改变，请重新上传。', VIDEO_ASSET_MISSING: '视频原件缺失，请重新上传。', VIDEO_RESOURCE_LIMIT: '处理超过资源限制，可拆分或降低分辨率后重试。' }[code] || '本地视频处理暂不可用，可稍后从检查点重试。'; },
    chooseFile(event) { const file = event.target.files[0]; if (!file) return; if (!/\.(mp4|webm)$/i.test(file.name) || file.size < 12 || file.size > 1073741824) { this.error = '请选择 1 GiB 以内的 MP4 / WebM 视频。'; event.target.value = ''; return; } this.file = file; this.fileIdentity = requestId(); this.nonce = requestId(); this.error = ''; if (!this.uploadTitle.trim()) this.uploadTitle = Array.from(file.name.replace(/\.(mp4|webm)$/i, '')).slice(0, 200).join(''); },
    async refresh() {
      if (!this.canMaintain || this.refreshing) return;
      const identity = this.identity, generation = this.generation, request = ++this.listRequest; this.refreshing = true;
      try { const data = responseData(await api.knowledgeVideoImports()); if (!this.current(identity, generation) || request !== this.listRequest) return; this.tasks = data.items; this.configured = data.configured; if (this.selected && ['QUEUED','RUNNING','FAILED'].includes(this.selected.state) && !this.hasUnsavedAuthoring()) await this.loadDetail(this.selected.id); }
      catch (error) { if (this.current(identity, generation)) this.error = errorMessage(error); }
      finally { if (this.current(identity, generation) && request === this.listRequest) this.refreshing = false; }
    },
    async loadDetail(id) {
      const identity = this.identity, generation = this.generation, request = ++this.detailRequest;
      const data = responseData(await api.knowledgeVideoImport(id));
      if (!this.current(identity, generation) || request !== this.detailRequest) return;
      this.selected = data; this.captions = data.content || ''; this.draftTitle = data.title; this.reviewed = false; this.asNew = false; this.saveNonce = requestId(); this.markAuthoringClean();
    },
    async select(id) { const identity = this.identity, generation = this.generation; if (!await confirmAuthoringDiscard(this) || !this.current(identity, generation)) return; try { this.error = ''; await this.loadDetail(id); } catch (error) { if (this.current(identity, generation)) this.error = errorMessage(error); } },
    async upload() {
      if (this.saving || !this.file || !this.uploadTitle.trim()) return;
      const identity = this.identity, generation = this.generation; this.saving = true; this.uploading = true; this.uploadProgress = 0; this.error = ''; this.uploadCancellation = axios.CancelToken.source();
      try {
        const data = responseData(await api.knowledgeUploadVideo(this.file, { title: this.uploadTitle, visibility: this.visibility, language: this.language, fileName: this.file.name, requestId: this.nonce, targetDocumentId: this.targetId || undefined, expectedRevision: this.targetId ? this.expectedRevision : undefined }, { cancelToken: this.uploadCancellation.token, onUploadProgress: event => { if (this.current(identity, generation) && event.total) this.uploadProgress = Math.min(100, Math.floor(event.loaded / event.total * 100)); } }));
        if (!this.current(identity, generation)) return; this.file = null; this.fileIdentity = ''; this.$refs.file.value = ''; this.markAuthoringClean(); await this.loadDetail(data.id); this.$message.success('原件已上传，正在本地转写。');
      } catch (error) { if (this.current(identity, generation) && !axios.isCancel(error)) this.error = errorMessage(error); }
      finally { if (this.current(identity, generation)) { this.saving = false; this.uploading = false; this.refresh(); } }
    },
    async save() {
      if (this.saving || !this.selected || this.selected.state !== 'READY') return;
      const identity = this.identity, generation = this.generation, task = this.selected;
      try { videoTranscript(this.captions); } catch (error) { this.error = error.message; return; }
      this.saving = true; this.error = '';
      try { const result = responseData(await api.knowledgeVideoCommand(task.id, 'save', { title: this.draftTitle, visibility: task.visibility, requestId: this.saveNonce, expectedRevision: task.expectedRevision, importRevision: task.revision, reviewedVideo: this.reviewed, content: this.reviewed ? this.captions : undefined, asNew: this.asNew })); if (!this.current(identity, generation)) return; await this.loadDetail(task.id); this.$emit('saved', result); this.$message.success('资料已保存并排队入库，发布后学生才能查看。'); }
      catch (error) { if (this.current(identity, generation)) this.error = errorMessage(error); }
      finally { if (this.current(identity, generation)) { this.saving = false; this.refresh(); } }
    },
    async mutate(action, extra, message) {
      if (this.saving || !this.selected) return;
      const identity = this.identity, generation = this.generation, task = this.selected;
      try { await this.$confirm(message, '视频任务', { confirmButtonText: '继续', cancelButtonText: '取消' }); if (!this.current(identity, generation)) return; this.saving = true; this.error = ''; const result = responseData(await api.knowledgeVideoCommand(task.id, action, { expectedRevision: task.revision, ...extra })); if (!this.current(identity, generation)) return; if (action === 'cancel') { this.selected = null; this.captions = ''; this.markAuthoringClean(); } else { this.selected = result; this.markAuthoringClean(); } }
      catch (error) { if (this.current(identity, generation) && error !== 'cancel' && error !== 'close') this.error = errorMessage(error); }
      finally { if (this.current(identity, generation)) { this.saving = false; this.refresh(); } }
    },
    retry(restart) { this.mutate('retry', { restart }, restart ? '重新识别全部语音？原视频保留，将清除当前转写检查点。' : '从已完成的检查点继续转写？'); },
    cancel() { this.mutate('cancel', {}, '取消转写并清理未保存的视频原件？已有资料版本保持不变。'); },
    async close() { const identity = this.identity, generation = this.generation; if (await confirmAuthoringDiscard(this) && this.current(identity, generation)) this.$emit('close'); },
  },
};
</script>
<style scoped>
.video-imports { margin-bottom: 24px; }.video-import-heading, .video-task-heading { display: flex; justify-content: space-between; align-items: baseline; gap: 16px; }
.video-import-heading h3 { margin: 0; font-size: 22px; }.video-import-heading p { margin: 8px 0 20px; }
.video-import-layout { display: grid; grid-template-columns: 290px minmax(0, 1fr); gap: 28px; }.video-import-layout aside { border-right: 1px solid var(--line); padding-right: 24px; min-width: 0; }
.video-upload h4, .video-task-heading h4, .video-review > h4 { font-size: 16px; margin: 0 0 16px; }.video-upload .knowledge-toolbar { margin: 12px 0; }.video-upload input { width: 100%; color: var(--muted); }.video-upload .el-button { margin: 12px 0; }
.video-task-heading { margin-top: 28px; }.video-task { display: block; width: 100%; text-align: left; padding: 14px 12px; background: transparent; border: 0; border-bottom: 1px solid var(--line); color: var(--text); cursor: pointer; }
.video-task strong, .video-task span, .video-task small { display: block; overflow-wrap: anywhere; }.video-task span, .video-task small { color: var(--muted); margin-top: 7px; font-size: 12px; }.video-task.selected { background: var(--soft); box-shadow: inset 3px 0 var(--accent); }
.video-task:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }.video-review { min-width: 0; }.video-review-form > label { display: block; font-size: 13px; margin: 22px 0; }.video-review-form > label .el-input { margin-top: 8px; }.video-review-form > .el-checkbox { margin-bottom: 18px; }.video-task-actions { margin-top: 24px; }
.video-review-save { position: sticky; top: 72px; z-index: 2; padding: 14px 0; margin-bottom: 18px; border-bottom: 1px solid var(--line); background: var(--surface); }
@media (max-width: 900px) { .video-import-layout { grid-template-columns: 1fr; }.video-import-layout aside { padding-right: 0; padding-bottom: 20px; border-right: 0; border-bottom: 1px solid var(--line); } }
</style>
