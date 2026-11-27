<template>
  <section class="remote-sources" v-loading="loading">
    <header class="remote-heading">
      <div><h2>{{ sourceName }} 题目目录</h2><p>{{ platform === 'nowcoder' ? '公开 ACM 目录每小时检查，首轮分批补齐；比赛题可按编号查找。' : platform === 'atcoder' ? '每小时更新目录，定时更新 AtCoder Problems 难度估计。' : '每小时更新来源题号、rating 和标签。' }}选题后获取原文，检查完成再开放到题库。</p></div>
      <div class="remote-actions">
        <CodeforcesCposImport v-if="platform === 'codeforces'" :key="identity" @imported="load(false)" />
        <RemoteCaptureQueue :key="'capture:' + identity" @imported="load(false)" />
        <el-button :loading="syncing" :disabled="status.state === 'RUNNING'" icon="el-icon-refresh" @click="sync">更新目录</el-button>
      </div>
    </header>
    <el-radio-group class="platform-switch" :value="platform" @input="changePlatform" aria-label="题目来源"><el-radio-button label="codeforces">Codeforces</el-radio-button><el-radio-button label="atcoder">AtCoder</el-radio-button><el-radio-button label="nowcoder">牛客 ACM</el-radio-button></el-radio-group>
    <div class="catalogue-status" aria-live="polite">
      <span>{{ stateText(status.state) }}</span><span>上次更新：{{ time(status.lastSuccessAt) }}</span><span>目录 {{ status.total || 0 }} 题</span>
      <span v-if="status.lastSuccessAt">新增 {{ status.added || 0 }} · 变更 {{ status.changed || 0 }} · 移出 {{ status.removed || 0 }}</span>
    </div>
    <p v-if="platform === 'nowcoder'" class="remote-notice">全量核对：{{ time(status.lastFullSuccessAt) }}<span v-if="status.state === 'RUNNING'"> · 正在处理第 {{ status.scanPage || 1 }} 页，来源共 {{ status.reportedTotal || '待确认' }} 题</span>。首轮完成前可先按题号查找。</p>
    <form v-if="platform === 'nowcoder'" class="remote-search" @submit.prevent="lookup">
      <el-input v-model="sourceId" aria-label="牛客题号或比赛题号" placeholder="NC218144 或 11244/A" maxlength="200" clearable />
      <el-button native-type="submit" :loading="discovering" :disabled="!sourceId.trim()" icon="el-icon-search">按题号查找</el-button>
    </form>
    <p v-if="status.lastError" class="remote-error">{{ status.lastError }}；已有目录仍可使用。</p>
    <form class="remote-search" @submit.prevent="search">
      <el-input v-model="keyword" aria-label="来源题号或标题" :placeholder="platform === 'nowcoder' ? '筛选已有目录，例如 NC-218144' : platform === 'atcoder' ? '来源题号或标题，例如 abc086_a' : '来源题号或标题，例如 4A'" clearable />
      <el-input-number v-model="ratingMin" :min="0" :max="10000" :controls="false" :placeholder="'最低' + difficultyLabel" aria-label="最低 rating" />
      <span>至</span><el-input-number v-model="ratingMax" :min="0" :max="10000" :controls="false" :placeholder="'最高' + difficultyLabel" aria-label="最高 rating" />
      <el-button native-type="submit" type="primary" icon="el-icon-search">筛选</el-button>
    </form>
    <el-table :data="records" empty-text="暂无目录，请更新目录或稍后重试。" class="remote-table">
      <el-table-column label="来源题目" min-width="260"><template slot-scope="scope">
        <a :href="scope.row.sourceUrl" target="_blank" rel="noopener noreferrer" class="remote-title">{{ scope.row.displayId }} · {{ scope.row.title }} <i class="el-icon-top-right" /></a>
        <div class="remote-tags">{{ scope.row.tags.join(' / ') }}</div>
      </template></el-table-column>
      <el-table-column :label="difficultyLabel" width="110"><template slot-scope="scope">{{ difficulty(scope.row) == null ? (platform === 'atcoder' ? '未知' : '未评级') : difficulty(scope.row) }}</template></el-table-column>
      <el-table-column label="题库状态" min-width="190"><template slot-scope="scope">
        <span>{{ scope.row.localId ? (scope.row.localAuth === 1 ? '已开放' : '待检查 / 未公开') : '未入库' }}</span>
        <div class="remote-notice" v-if="scope.row.importState">{{ stateText(scope.row.importState) }}</div>
        <div class="remote-notice" v-if="scope.row.translationStale">原文已更新，{{ translationLanguage === 'zh' ? '中文' : 'English' }} 待核对</div>
        <div class="remote-notice" v-if="scope.row.metadataChanged">来源信息有更新</div>
        <div class="remote-error" v-if="scope.row.importError">{{ scope.row.importError }}</div>
      </template></el-table-column>
      <el-table-column label="操作" min-width="250"><template slot-scope="scope">
        <div class="remote-actions">
          <el-button size="small" :loading="!!busy[scope.row.problemKey]" :disabled="pending(scope.row) || scope.row.importState === 'CONFLICT'" @click="request(scope.row)">{{ scope.row.localId ? '刷新原文' : '获取题面' }}</el-button>
          <el-button size="small" :disabled="scope.row.importState === 'CONFLICT'" @click="open(scope.row)">{{ editorLabel(scope.row) }}</el-button>
          <a v-if="scope.row.importError && scope.row.sourceUrl" :href="scope.row.sourceUrl" target="_blank" rel="noopener noreferrer">查看源站</a>
          <router-link v-if="scope.row.localId && scope.row.importState !== 'CONFLICT'" :to="{ name: 'admin-edit-problem', params: { problemId: scope.row.localId } }">检查与开放</router-link>
        </div>
      </template></el-table-column>
    </el-table>
    <div class="remote-mobile-list"><article v-for="row in records" :key="row.problemKey"><a class="remote-title" :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">{{ row.displayId }} · {{ row.title }} <i class="el-icon-top-right" /></a><p>{{ difficulty(row) == null ? (platform === 'atcoder' ? '难度未知' : '未评级') : difficultyLabel + ' ' + difficulty(row) }} · {{ row.localId ? (row.localAuth === 1 ? '已开放' : '待检查 / 未公开') : '未入库' }}</p><p class="remote-notice">{{ row.tags.join(' / ') }}</p><p class="remote-notice">{{ stateText(row.importState) }}</p><p v-if="row.translationStale" class="remote-notice">原文已更新，中文待核对</p><p v-if="row.importError" class="remote-error">{{ row.importError }}</p><div class="remote-actions"><el-button size="small" :loading="!!busy[row.problemKey]" :disabled="pending(row) || row.importState === 'CONFLICT'" @click="request(row)">{{ row.localId ? '刷新原文' : '获取题面' }}</el-button><el-button size="small" :disabled="row.importState === 'CONFLICT'" @click="open(row)">{{ editorLabel(row) }}</el-button><a v-if="row.importError && row.sourceUrl" :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">查看源站</a><router-link v-if="row.localId && row.importState !== 'CONFLICT'" :to="{ name: 'admin-edit-problem', params: { problemId: row.localId } }">检查与开放</router-link></div></article></div>
    <el-pagination :current-page="page" :page-size="30" :total="total" layout="prev, pager, next, total" @current-change="changePage" />
    <p v-if="platform === 'nowcoder'" class="remote-footnote">来源 Rating 来自牛客公开目录，与站内 Rating 分开展示；普通题和比赛题分别保留来源编号。中文为原文，English 译文由管理员核对录入。</p>
    <p v-if="platform === 'atcoder'" class="remote-footnote">难度估计来自 AtCoder Problems，不是官方评级或站内 Rating；新入库题的站内 Rating 留空。未提供英文的旧题需要管理员补录英文原文。</p>
    <p class="remote-footnote">{{ platform === 'codeforces' ? 'CF 获取失败时，可在原文编辑器导入网页 HTML。' : '题面无法获取时，可打开源站后录入原文。' }}新入库题目保存后待检查与开放。</p>
    <el-dialog :visible.sync="visible" :title="target ? target.displayId + ' · 原文与译文' : '原文与译文'" width="90%" top="5vh" append-to-body :close-on-click-modal="false" :before-close="closeEditor" custom-class="remote-statement-dialog">
      <div v-loading="opening">
        <template v-if="detail && form">
          <div class="remote-editor-heading"><el-radio-group :value="language" @input="changeLanguage"><el-radio-button :label="sourceLanguage">{{ sourceLanguage === 'en' ? 'English 原文' : '中文原文' }}</el-radio-button><el-radio-button :label="translationLanguage" :disabled="!detail.source">{{ translationLanguage === 'zh' ? '中文译文' : 'English 译文' }}</el-radio-button></el-radio-group><a :href="detail.sourceUrl" target="_blank" rel="noopener noreferrer">查看源站 <i class="el-icon-top-right" /></a></div>
          <p class="remote-notice" v-if="!detail.source">尚未获取原文。可在这里录入；保存后会创建一题未公开的编程题。</p>
          <p class="remote-notice" v-else>原文版本 {{ detail.source.contentHash.slice(0, 10) }} · {{ detail.source.origin === 'SOURCE' ? '源站获取' : '管理员录入' }} · {{ time(detail.source.createdAt) }}</p>
          <div v-if="detail.source && detail.refresh" class="statement-refresh" aria-live="polite">
            <el-switch :value="detail.refresh.enabled" :disabled="policySaving || saving" @change="setRefresh" active-text="每天自动检查原文" />
            <p>最近检查：{{ time(detail.refresh.lastCheckedAt) }} · 最近成功：{{ time(detail.refresh.lastSuccessAt) }} · 下次检查：{{ detail.refresh.enabled ? time(detail.refresh.nextCheckAt) : '已关闭' }}</p>
            <p v-if="detail.refresh.lastError" class="remote-error">{{ detail.refresh.lastError }}；已有题面保留。</p>
            <p>自动获取的新原文会同步到未修改的题库副本；题面有手工修改时保留整份副本。原文改变后，译文需重新核对；人工保存原文会关闭自动检查。</p>
          </div>
          <el-alert v-if="detail.translationStale" title="原文已更新，旧译文已停止展示。请切换译文，核对后重新保存。" type="warning" :closable="false" show-icon />
          <codeforces-html-import
            v-if="platform === 'codeforces' && language === sourceLanguage"
            :key="identity + ':' + target.problemKey + ':' + epoch"
            :problem-key="target.problemKey"
            :disabled="saving || policySaving"
            @parsed="applyCodeforcesHtml"
          />
          <remote-translation-draft v-if="detail.source && language === translationLanguage" :key="identity + ':' + target.problemKey + ':' + language + ':' + detail.source.contentHash" ref="translationDraft" :problem-key="target.problemKey" :source-hash="detail.source.contentHash" :target-language="translationLanguage === 'zh' ? 'zh-CN' : 'en'" :disabled="saving || policySaving || applyingTranslation" @draft="hasTranslationDraft = $event" @apply="applyTranslation" />
          <el-form ref="remoteForm" label-position="top" class="remote-form" :disabled="saving">
            <el-form-item label="标题" required><el-input v-model="form.title" maxlength="255" /></el-form-item>
            <div class="remote-limits" v-if="language === sourceLanguage"><el-form-item label="时间限制（ms）"><el-input-number v-model="form.timeLimit" :min="1" :max="3600000" /></el-form-item><el-form-item label="内存限制（MB）"><el-input-number v-model="form.memoryLimit" :min="1" :max="1048576" /></el-form-item></div>
            <el-form-item label="题目描述" required><Editor :key="editorKey" v-model="form.description" /></el-form-item>
            <el-form-item label="输入"><el-input type="textarea" :rows="3" v-model="form.input" /></el-form-item>
            <el-form-item label="输出"><el-input type="textarea" :rows="3" v-model="form.output" /></el-form-item>
            <el-form-item label="样例"><div v-for="(example, index) in form.examples" :key="index" class="remote-example"><el-input type="textarea" :rows="3" v-model="example.input" aria-label="样例输入" placeholder="样例输入" /><el-input type="textarea" :rows="3" v-model="example.output" aria-label="样例输出" placeholder="样例输出" /><el-button icon="el-icon-delete" aria-label="删除样例" @click="form.examples.splice(index, 1)" /></div><el-button size="small" icon="el-icon-plus" @click="form.examples.push({ input: '', output: '' })">添加样例</el-button></el-form-item>
            <el-form-item label="提示与说明"><el-input type="textarea" :rows="4" v-model="form.hint" /></el-form-item>
          </el-form>
        </template>
      </div>
      <span slot="footer"><el-button :disabled="saving" @click="closeEditor">关闭</el-button><el-button type="primary" :loading="saving" :disabled="!form || opening || policySaving || applyingTranslation" @click="save">{{ language === sourceLanguage ? '保存原文' : '核对并保存译文' }}</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import api from '@/common/api';
import utils from '@/common/utils';
import Editor from '@/components/admin/Editor';
import authoringGuard, { confirmAuthoringDiscard } from '@/common/authoringGuard';
import RemoteTranslationDraft from '@/components/admin/RemoteTranslationDraft';
import CodeforcesHtmlImport from '@/components/admin/CodeforcesHtmlImport';
import CodeforcesCposImport from '@/components/admin/CodeforcesCposImport';
import RemoteCaptureQueue from '@/components/admin/RemoteCaptureQueue';
export default {
  name: 'RemoteSources', components: { Editor, RemoteTranslationDraft, CodeforcesHtmlImport, CodeforcesCposImport, RemoteCaptureQueue }, mixins: [authoringGuard],
  data: () => ({ hasTranslationDraft: false, applyingTranslation: false, formBaseline: '', examplesRaw: '', examplesBaseline: '', platform: 'codeforces', sourceId: '', discovering: false, policySaving: false, loading: false, syncing: false, opening: false, saving: false, records: [], total: 0, status: {}, page: 1, keyword: '', ratingMin: undefined, ratingMax: undefined, filters: {}, busy: {}, visible: false, target: null, detail: null, form: null, language: 'en', editorKey: 0, epoch: 0, listEpoch: 0, disposed: false }),
  computed: {
    sourceLanguage() { return this.platform === 'nowcoder' ? 'zh' : 'en'; },
    translationLanguage() { return this.sourceLanguage === 'zh' ? 'en' : 'zh'; },
    sourceName() { return this.platform === 'nowcoder' ? '牛客 ACM' : this.platform === 'atcoder' ? 'AtCoder' : 'Codeforces'; },
    difficultyLabel() { return this.platform === 'nowcoder' ? '来源 Rating' : this.platform === 'atcoder' ? '难度估计' : 'Rating'; },
    identity() { return [this.$store.getters.userInfo.uid, this.$store.getters.authEpoch, (this.$store.getters.userInfo.roleList || []).slice().sort().join(',')].join(':'); },
    authoringState() { return this.visible && this.form ? { key: this.target.problemKey, language: this.language, form: this.form, translationDraft: this.hasTranslationDraft } : null; },
  },
  watch: { identity() { this.epoch++; this.listEpoch++; this.visible = false; this.form = this.detail = this.target = null; this.busy = {}; this.applyingTranslation = this.hasTranslationDraft = this.discovering = this.policySaving = this.saving = this.opening = this.syncing = false; this.records = []; this.load(); } },
  mounted() { this.load(); this.poll = setInterval(() => { if (!this.loading && !document.hidden) this.load(false); }, 15000); },
  beforeDestroy() { this.disposed = true; this.epoch++; this.listEpoch++; clearInterval(this.poll); },
  methods: {
    editorLabel(row) {
      if (row.localId) return '原文与译文';
      return this.platform === 'codeforces' ? '导入或录入原文' : '手动录入原文';
    },
    difficulty(row) { return this.platform === 'nowcoder' ? row.sourceRating : this.platform === 'atcoder' ? row.estimatedDifficulty : row.rating; },
    async changePlatform(platform) {
      if (!['codeforces', 'atcoder', 'nowcoder'].includes(platform) || platform === this.platform || !await this.discard()) return;
      this.epoch++; this.listEpoch++; this.visible = false; this.form = this.detail = this.target = null;
      this.platform = platform; this.records = []; this.total = 0; this.status = {}; this.page = 1;
      this.keyword = ''; this.ratingMin = this.ratingMax = undefined; this.filters = {}; this.busy = {}; this.sourceId = ''; this.applyingTranslation = this.hasTranslationDraft = false; this.discovering = this.policySaving = this.syncing = this.opening = this.saving = false;
      this.load();
    },
    current(epoch, identity) { return !this.disposed && this.epoch === epoch && this.identity === identity; },
    async discard() { const epoch = this.epoch, identity = this.identity; return await confirmAuthoringDiscard(this) && this.current(epoch, identity); },
    time(value) { return value ? new Date(value).toLocaleString() : '尚未更新'; },
    stateText(value) { return ({ QUEUED: '等待获取', RUNNING: '正在获取', BACKOFF: '等待重试', IDLE: '目录已更新', DONE: '题面已保存', FAILED: '获取未完成，可录入原文', CONFLICT: '题号被其他题目占用' })[value] || value || ''; },
    pending(row) { return ['QUEUED', 'RUNNING', 'BACKOFF'].includes(row.importState); },
    async load(indicator = true) {
      const epoch = ++this.listEpoch, identity = this.identity;
      if (indicator) this.loading = true;
      try { const res = await api.remoteCatalogue({ page: this.page, ...this.filters }, this.platform); if (this.disposed || epoch !== this.listEpoch || identity !== this.identity) return; this.records = res.data.data.records; this.total = res.data.data.total; this.status = res.data.data.status; }
      catch (_) {} finally { if (!this.disposed && epoch === this.listEpoch) this.loading = false; }
    },
    search() { this.filters = { keyword: this.keyword, ratingMin: this.ratingMin, ratingMax: this.ratingMax }; this.page = 1; this.load(); },
    changePage(page) { this.page = page; this.load(); },
    async sync() { const epoch = this.epoch, identity = this.identity; this.syncing = true; try { await api.remoteCatalogueSync(this.platform); if (this.current(epoch, identity)) { this.$message.success('目录更新已排队'); this.load(); } } catch (_) {} finally { if (this.current(epoch, identity)) this.syncing = false; } },
    async lookup() {
      if (this.discovering || this.platform !== 'nowcoder' || !this.sourceId.trim()) return;
      const epoch = this.epoch, identity = this.identity; this.discovering = true;
      try { const res = await api.remoteNowCoderLookup(this.sourceId); if (!this.current(epoch, identity)) return; this.keyword = res.data.data.displayId; this.filters = { keyword: res.data.data.problemKey }; this.page = 1; this.load(); this.$message.success('已找到题目，可获取原文后检查开放'); }
      catch (_) {} finally { if (this.current(epoch, identity)) this.discovering = false; }
    },
    async request(row) { const epoch = this.epoch, identity = this.identity; this.$set(this.busy, row.problemKey, true); try { await api.remoteImport(row.problemKey); if (this.current(epoch, identity)) { this.$message.success('题面获取已排队'); this.load(); } } catch (_) {} finally { if (this.current(epoch, identity)) this.$delete(this.busy, row.problemKey); } },
    async open(row) {
      if (!await this.discard()) return;
      const epoch = ++this.epoch, identity = this.identity; this.target = row; this.detail = this.form = null; this.language = this.sourceLanguage; this.visible = true; this.opening = true;
      try { const res = await api.remoteStatement(row.problemKey); if (!this.current(epoch, identity)) return; this.detail = res.data.data; this.resetForm(); }
      catch (_) { if (this.current(epoch, identity)) this.visible = false; } finally { if (this.current(epoch, identity)) this.opening = false; }
    },
    resetForm() {
      this.hasTranslationDraft = false;
      this.applyingTranslation = false;
      if (this.$refs.translationDraft) this.$refs.translationDraft.clearDraft();
      const version = this.language === this.sourceLanguage ? this.detail.source : this.detail.translation;
      const source = this.detail.source && this.detail.source.content;
      const content = version ? version.content : { title: source ? source.title : this.target.title, description: '', input: '', output: '', hint: '', examples: '', timeLimit: 1000, memoryLimit: 256 };
      this.fillForm(content);
      this.formBaseline = JSON.stringify(this.form);
      this.markAuthoringClean();
    },
    fillForm(content) {
      this.form = { ...content, examples: utils.stringToExamples(content.examples || '') };
      this.examplesRaw = content.examples || '';
      this.examplesBaseline = JSON.stringify(this.form.examples);
      this.editorKey++;
    },
    async applyCodeforcesHtml(preview) {
      if (!this.visible || !this.form || this.saving || this.policySaving || this.platform !== 'codeforces' || this.language !== this.sourceLanguage) return;
      const epoch = this.epoch, identity = this.identity, before = JSON.stringify(this.form);
      const current = () => this.current(epoch, identity) && this.visible && this.target.problemKey === preview.problemKey;
      if (!current()) return;

      const confirm = [];
      if (preview.catalogTitle && preview.catalogTitle !== preview.content.title) {
        confirm.push('网页标题「' + preview.content.title + '」与目录标题「' + preview.catalogTitle + '」有差异，请先核对题号。');
      }
      if (before !== this.formBaseline) confirm.push('填入的内容会替换当前编辑中的原文。');
      if (confirm.length) {
        try { await this.$confirm(confirm.join(''), '填入 CF 题面', { type: 'warning' }); } catch (_) { return; }
      }
      if (!current() || this.saving || this.policySaving || before !== JSON.stringify(this.form)) return;
      this.fillForm(preview.content);
      this.$message.success('题面已填入，请校对后保存');
    },
    async changeLanguage(language) { if (this.saving || this.policySaving || this.applyingTranslation || language === this.language || !await this.discard() || !this.visible) return; this.epoch++; this.language = language; this.resetForm(); },
    async applyTranslation(draft) {
      if (this.saving || this.policySaving || this.applyingTranslation || !this.visible || !this.detail || !this.detail.source || this.language !== this.translationLanguage) return;
      const epoch = this.epoch, identity = this.identity, language = this.language, before = JSON.stringify(this.form);
      const current = () => this.current(epoch, identity) && this.visible && this.language === language && this.target.problemKey === draft.problemKey && this.detail.source.contentHash === draft.sourceHash;
      if (!current()) return; this.applyingTranslation = true;
      try {
        if (before !== this.formBaseline) { try { await this.$confirm('应用草稿会替换当前编辑中的译文，是否继续？', '应用翻译草稿', { type: 'warning' }); } catch (_) { return; } }
        if (!current() || JSON.stringify(this.form) !== before) return;
        const detail = (await api.remoteStatement(draft.problemKey)).data.data;
        if (!current() || JSON.stringify(this.form) !== before) return;
        if (!detail.source || detail.source.contentHash !== draft.sourceHash) { this.$message.warning('原文已更新，请重新打开题目后生成译文。当前编辑已保留。'); return; }
        this.fillForm(draft.content);
        if (this.$refs.translationDraft) this.$refs.translationDraft.markApplied();
        this.$nextTick(() => { if (current() && this.$refs.remoteForm) this.$refs.remoteForm.$el.scrollIntoView({ block: 'start', behavior: 'auto' }); });
      } catch (_) {} finally { if (this.current(epoch, identity)) this.applyingTranslation = false; }
    },
    async closeEditor(done) { if (!await this.discard()) return; this.epoch++; this.visible = false; this.form = this.detail = this.target = null; if (typeof done === 'function') done(); },
    async setRefresh(enabled) {
      if (this.policySaving || this.saving || !this.detail || !this.detail.source) return;
      const epoch = this.epoch, identity = this.identity, key = this.target.problemKey; this.policySaving = true;
      try { const res = await api.remoteStatementRefresh(key, enabled); if (!this.current(epoch, identity)) return; this.$set(this.detail, 'refresh', res.data.data.refresh); this.$set(this.detail, 'import', res.data.data.import); this.$message.success(enabled ? '已开启每天检查' : '已关闭自动检查'); }
      catch (_) {} finally { if (this.current(epoch, identity)) this.policySaving = false; }
    },
    async save() {
      if (this.policySaving || this.saving || this.applyingTranslation || !this.form || !this.form.title.trim() || !this.form.description.trim()) { if (!this.saving && !this.applyingTranslation) this.$message.warning('请填写标题与题目描述'); return; }
      const epoch = this.epoch, identity = this.identity; this.saving = true;
      const data = { problemKey: this.target.problemKey, sourceHash: this.detail.source ? this.detail.source.contentHash : null, content: { ...this.form, examples: JSON.stringify(this.form.examples) === this.examplesBaseline ? this.examplesRaw : utils.examplesToString(this.form.examples) } };
      try { const res = await api.remoteStatementSave(this.language, data); if (!this.current(epoch, identity)) return; this.detail = res.data.data; this.resetForm(); this.$message.success(this.language === this.sourceLanguage ? '原文已保存，请检查题库开放设置' : '译文已保存并绑定当前原文'); this.load(false); }
      catch (_) {} finally { if (this.current(epoch, identity)) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.statement-refresh { margin: 18px 0; padding: 14px 0; border-block: 1px solid var(--line); }.statement-refresh p { color: var(--muted); font-size: 12px; line-height: 1.8; margin: 8px 0 0; }
.remote-sources { color: var(--text); }.remote-heading { display: flex; justify-content: space-between; gap: 20px; align-items: flex-start; margin: 16px 0; }.remote-heading h2 { font-size: 21px; margin: 0 0 10px; }.remote-heading p,.remote-notice,.remote-footnote { font-size: 12px; line-height: 1.8; color: var(--muted); }.remote-heading p { margin: 0; max-width: 65em; }.catalogue-status { display: flex; flex-wrap: wrap; gap: 8px 24px; padding: 14px 0; border-bottom: 1px solid var(--line); font-size: 12px; color: var(--muted); }.catalogue-status>span:first-child { color: var(--accent); }.remote-search { display: flex; align-items: center; gap: 12px; margin: 22px 0; }.remote-search>.el-input { max-width: 400px; }.remote-search .el-input-number { width: 130px; flex-shrink: 0; }.remote-search>span { color: var(--muted); }.remote-title { color: var(--accent); line-height: 1.7; }.remote-tags { color: var(--muted); font-size: 11px; margin-top: 5px; }.remote-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }.remote-actions .el-button { margin: 0; }.remote-actions a { color: var(--accent); font-size: 12px; }.remote-error { font-size: 12px; color: var(--danger, #dd625c); line-height: 1.8; }.el-pagination { margin-top: 20px; }.remote-footnote { margin-top: 22px; }.remote-editor-heading { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }.remote-editor-heading a { color: var(--accent); }.remote-form { margin-top: 24px; }.remote-limits { display: flex; flex-wrap: wrap; gap: 24px; }.remote-example { display: flex; gap: 10px; align-items: flex-start; margin-bottom: 10px; }.remote-example>.el-textarea { flex: 1; min-width: 0; }a:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.remote-mobile-list { display: none; }.remote-mobile-list article { padding: 18px 0; border-bottom: 1px solid var(--line); }.remote-mobile-list article>p { font-size: 12px; line-height: 1.8; }
@media(max-width:700px) { .remote-heading { flex-direction: column; }.remote-search { flex-wrap: wrap; }.remote-search>.el-input { max-width: none; }.remote-search .el-input-number { width: 105px; }.remote-example { flex-wrap: wrap; }.remote-example>.el-textarea { flex-basis: 100%; }.remote-table { display: none; }.remote-mobile-list { display: block; } }
</style>
<style>
.remote-statement-dialog .el-dialog__body { max-height: 70vh; overflow: auto; }.remote-statement-dialog .v-note-wrapper { min-width: 0; }
</style>
