<template>
  <section class="paper-admin" :class="{ 'editor-only': editorOnly }">
    <template v-if="!editorOnly">
    <header class="admin-heading"><div><h1>整套试卷</h1><p>引用已有题目组成试卷；单题练习与套卷分别记录，已开始的答卷保留原题。</p></div><el-button type="primary" @click="create">新建试卷</el-button></header>
    <form class="search" @submit.prevent="reset"><el-input v-model="keyword" clearable maxlength="100" placeholder="搜索试卷名称" /><el-button native-type="submit">搜索</el-button></form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-loading="loading" :data="rows" empty-text="暂无试卷，点击新建试卷开始组卷"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="title" label="名称" min-width="220" /><el-table-column prop="questionCount" label="题数" width="80" /><el-table-column label="编程判题" width="150"><template slot-scope="{row}">{{ row.programMode === 'LIVE' ? '即时判题' : '交卷后判题' }}</template></el-table-column><el-table-column label="状态" width="100"><template slot-scope="{row}"><el-tag :type="row.published ? 'success' : 'info'">{{ row.published ? '已发布' : '草稿' }}</el-tag></template></el-table-column><el-table-column label="操作" width="110"><template slot-scope="{row}"><el-button type="text" @click="edit(row.id)">编辑试卷</el-button></template></el-table-column></el-table>
    <el-pagination layout="prev, pager, next, total" :page-size="20" :total="total" :current-page="page" @current-change="changePage" />
    </template>
    <el-dialog title="组卷与开放设置" :visible.sync="dialog" width="min(980px, 94vw)" top="4vh" custom-class="paper-editor-dialog" append-to-body :close-on-click-modal="false" :before-close="close">
      <authoring-steps v-show="!preview" :value="step" :steps="['基本信息', '选题与分值', '规则与开放']" :disabled="saving" @input="goStep" />
      <div v-if="form && preview" class="paper-preview"><el-tag>试卷预览</el-tag><h2>{{ form.title }}</h2><p class="preview-text">{{ form.description }}</p><p class="help">共 {{ form.items.length }} 题 · {{ totalScore }} 分 · {{ form.programMode === 'LIVE' ? '编程即时判题' : '交卷后统一判题' }}</p><ol><li v-for="item in form.items" :key="item.kind + ':' + item.referenceId"><strong>{{ title(item) }}</strong><span>{{ item.kind === 'PROGRAMMING' ? '编程题' : questionTypes[meta(item).questionType] }} · {{ item.score }} 分</span><small>来源：{{ meta(item).source || '本校题库' }}</small></li></ol><p class="help">{{ form.published ? '保存后开放日常整卷答题。' : '保存后仅管理端可见；可先用于比赛配置。' }} 单题是否开放由题库中的设置决定。</p></div>
      <el-form v-if="form" v-show="!preview" label-position="top" @submit.native.prevent>
        <div v-show="step === 0">
        <el-form-item label="试卷名称"><el-input v-model="form.title" maxlength="200" placeholder="例如：蓝桥杯第十六届省赛 A 组" /></el-form-item><el-form-item label="试卷说明"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="5000" /></el-form-item>
        </div>
        <div v-show="step === 1">
        <div class="section-heading"><h3>试卷题目</h3><span>{{ form.items.length }} / 50 题 · {{ totalScore }} 分</span></div>
        <div class="question-picker"><el-select v-model="kind" aria-label="题库" @change="searchChoices('')"><el-option label="客观题（含填空）" value="OBJECTIVE" /><el-option label="编程题" value="PROGRAMMING" /></el-select><el-select v-model="candidate" filterable remote :remote-method="searchChoices" :loading="choiceLoading" placeholder="输入题目标题搜索" aria-label="搜索题库"><el-option v-for="item in choices" :key="item.id" :label="(item.problemId || item.id) + ' · ' + item.title" :value="item.id" /></el-select><el-button :disabled="!candidate || form.items.length >= 50 || choiceLoading" @click="add">添加题目</el-button></div>
        <el-button type="text" icon="el-icon-plus" :disabled="form.items.length >= 50" @click="$refs.creator.open()">录入新题并加入试卷</el-button>
        <p class="help">可引用有权管理的保密题，无需先开放单题练习。填空题按各空分值计分；编程题支持本地标准判题。</p>
        <ol class="selected-items"><li v-for="(item, index) in form.items" :key="item.kind + ':' + item.referenceId"><span class="order">{{ index + 1 }}</span><div class="item-title"><strong>{{ title(item) }}</strong><small>{{ item.kind === 'PROGRAMMING' ? '编程题' : questionTypes[meta(item).questionType] || '客观题' }} · 原题 {{ item.referenceId }}</small></div><el-input-number v-model="item.score" :min="1" :max="20000" :precision="0" :disabled="meta(item).questionType === 'FILL_BLANK'" size="small" aria-label="本题分值" /><span>分</span><el-button size="mini" icon="el-icon-arrow-up" :disabled="index === 0" aria-label="上移题目" @click="move(index, -1)" /><el-button size="mini" icon="el-icon-arrow-down" :disabled="index === form.items.length - 1" aria-label="下移题目" @click="move(index, 1)" /><el-button type="text" @click="form.items.splice(index, 1)">移除</el-button></li></ol>
        </div>
        <div v-show="step === 2">
        <div class="publication-summary"><strong>{{ form.title }}</strong><p>{{ form.items.length }} 题 · {{ totalScore }} 分</p><p>{{ form.published ? '保存后开放日常整卷答题。' : '仅管理端可见，可用于比赛配置。' }} 题目的单题练习开关独立控制。</p></div>
        <div class="rule-grid"><el-form-item label="编程题判题时机"><el-select v-model="form.programMode"><el-option label="作答时多次提交，即时查看结果" value="LIVE" /><el-option label="保存草稿，交卷后统一判题" value="AFTER_FINISH" /></el-select></el-form-item><el-form-item label="编程题计分"><el-select v-model="form.scoreRule"><el-option label="最高分" value="BEST" /><el-option label="最后一次提交" value="LAST" /></el-select></el-form-item></div>
        <p class="help">即时模式会在作答时开放编程判题结果；交卷后判题模式按下面的答案开放条件显示成绩。客观题均交卷后判分。编程题 ACM 全对得满分，OI 按原题总分比例折算。</p>
        <div class="rule-grid"><el-form-item label="答案与成绩开放条件"><el-select v-model="form.answerPolicy"><el-option label="交卷并判题完成后" value="AFTER_FINISH" /><el-option label="指定时间，且已交卷" value="AT_TIME" /><el-option label="暂不开放" value="NEVER" /></el-select><el-date-picker v-if="form.answerPolicy === 'AT_TIME'" v-model="form.answerReleaseAt" type="datetime" value-format="timestamp" placeholder="答案开放时间" /></el-form-item><el-form-item label="解析开放条件"><el-select v-model="form.explanationPolicy"><el-option label="交卷后" value="AFTER_FINISH" /><el-option label="指定时间，且已交卷" value="AT_TIME" /><el-option label="暂不开放" value="NEVER" /></el-select><el-date-picker v-if="form.explanationPolicy === 'AT_TIME'" v-model="form.explanationReleaseAt" type="datetime" value-format="timestamp" placeholder="解析开放时间" /></el-form-item></div>
        <p class="help">修改题目、顺序、分值、判题时机与计分规则仅影响之后开始的答卷；开放条件的调整也影响已有答卷。下架后暂停学生访问。需要保密的题目请同时关闭单题练习入口。</p>
        <el-form-item label="开放日常整卷答题"><el-switch v-model="form.published" /><span class="help">关闭时仍可保存并用于比赛；比赛中按比赛权限和开放规则访问。发布试卷不会自动开放其中题目的单题练习。</span></el-form-item>
        </div><el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      </el-form>
      <div slot="footer" class="authoring-footer"><el-button :disabled="saving" @click="close()">取消</el-button><div><el-button v-if="step > 0 && !preview" :disabled="saving" @click="goStep(step - 1)">上一步</el-button><el-button :disabled="saving" @click="preview = !preview">{{ preview ? '继续编辑' : '预览试卷' }}</el-button><el-button v-if="step < 2 && !preview" type="primary" @click="goStep(step + 1)">下一步</el-button><el-button v-else-if="step === 2" type="primary" :loading="saving" @click="save">{{ form && form.published ? '保存并开放试卷' : '保存试卷' }}</el-button></div></div>
    </el-dialog>
    <question-creator v-if="dialog" ref="creator" @saved="addCreatedQuestion" />
  </section>
</template>
<script>
import api from '@/common/api';
import { questionTypes, responseData, errorMessage } from '@/common/objective';
import QuestionCreator from '@/components/admin/QuestionCreator';
import AuthoringSteps from '@/components/admin/AuthoringSteps';
import authoringGuard, { confirmAuthoringDiscard } from '@/common/authoringGuard';
const defaults = () => ({ title: '', description: '', items: [], programMode: 'AFTER_FINISH', scoreRule: 'BEST', answerPolicy: 'AFTER_FINISH', explanationPolicy: 'AFTER_FINISH', answerReleaseAt: null, explanationReleaseAt: null, published: false });
export default {
  name: 'PaperAdmin',
  components: { QuestionCreator, AuthoringSteps },
  mixins: [authoringGuard],
  props: { editorOnly: Boolean },
  data: () => ({ rows: [], total: 0, page: 1, keyword: '', loading: false, error: '', saving: false, dialog: false, form: null, formError: '', kind: 'OBJECTIVE', candidate: null, choices: [], choiceLoading: false, metadata: {}, request: 0, formRequest: 0, choiceRequest: 0, questionTypes, preview: false, step: 0 }),
  computed: { authoringState() { return this.dialog ? this.form : null; }, uid() { return this.$store.getters.userInfo.uid; }, totalScore() { return this.form ? this.form.items.reduce((total, item) => total + (item.score || 0), 0) : 0; } },
  watch: { uid() { this.request++; this.formRequest++; this.choiceRequest++; this.form = null; this.dialog = false; this.saving = false; this.metadata = {}; this.reset(); } },
  created() { this.load(); }, beforeDestroy() { this.request++; this.formRequest++; this.choiceRequest++; },
  methods: {
    meta(item) { return this.metadata[item.kind + ':' + item.referenceId] || {}; }, title(item) { return this.meta(item).title || '题目 ' + item.referenceId; },
    reset() { this.page = 1; this.load(); }, changePage(page) { this.page = page; this.load(); },
    async load() { if (this.editorOnly) return; const token = ++this.request; this.loading = true; this.error = ''; try { const data = responseData(await api.adminPapers({ currentPage: this.page, keyword: this.keyword })); if (token === this.request) { this.rows = data.records; this.total = data.total; } } catch (e) { if (token === this.request) this.error = errorMessage(e); } finally { if (token === this.request) this.loading = false; } },
    create() { if (this.saving) return; this.formRequest++; this.form = defaults(); this.step = 0; this.preview = false; this.formError = ''; this.dialog = true; this.markAuthoringClean(); this.searchChoices(''); },
    async edit(id) {
      if (this.saving) return; const token = ++this.formRequest; this.error = '';
      try {
        const form = responseData(await api.adminPaper(id));
        const data = await Promise.all(form.items.map(async item => item.kind === 'OBJECTIVE' ? responseData(await api.adminObjectiveQuestion(item.referenceId)) : { id: item.referenceId, title: '编程题 ' + item.referenceId }));
        const programs = responseData(await api.adminPaperPrograms({}));
        if (token !== this.formRequest) return;
        form.items.forEach((item, i) => { this.$set(this.metadata, item.kind + ':' + item.referenceId, item.kind === 'PROGRAMMING' ? programs.find(p => p.id === item.referenceId) || data[i] : data[i]); if (data[i].questionType === 'FILL_BLANK') item.score = data[i].blanks.reduce((sum, blank) => sum + blank.score, 0); });
        this.form = form; this.step = 0; this.preview = false; this.formError = ''; this.dialog = true; this.markAuthoringClean(); this.searchChoices('');
      } catch (e) { if (token === this.formRequest) { this.error = errorMessage(e); this.$emit('error', this.error); } }
    },
    async close(done) { const revision = this.formRequest; if (!await confirmAuthoringDiscard(this) || revision !== this.formRequest) return; this.dialog = false; this.formRequest++; this.choiceRequest++; if (typeof done === 'function') done(); },
    goStep(step) {
      if (this.saving) return;
      if (step > 0 && !this.form.title.trim()) { this.step = 0; this.formError = '请填写试卷名称。'; return; }
      if (step > 1 && !this.form.items.length) { this.step = 1; this.formError = '请至少添加一道题。'; return; }
      this.step = step; this.formError = '';
    },
    async searchChoices(keyword) { const token = ++this.choiceRequest; this.choiceLoading = true; this.candidate = null; this.choices = []; try { const data = responseData(await (this.kind === 'OBJECTIVE' ? api.adminObjectiveQuestions({ keyword, limit: 100 }) : api.adminPaperPrograms({ keyword }))); if (token === this.choiceRequest) this.choices = this.kind === 'OBJECTIVE' ? data.records : data; } catch (e) { if (token === this.choiceRequest) this.formError = errorMessage(e); } finally { if (token === this.choiceRequest) this.choiceLoading = false; } },
    async add() {
      if (!this.candidate || this.choiceLoading || this.form.items.length >= 50) return;
      const token = this.formRequest, kind = this.kind, id = this.candidate, key = kind + ':' + id;
      if (this.form.items.some(item => item.kind + ':' + item.referenceId === key)) { this.formError = '同一试卷不能重复添加同一道题'; return; }
      this.choiceLoading = true; this.formError = '';
      try { const meta = kind === 'OBJECTIVE' ? responseData(await api.adminObjectiveQuestion(id)) : this.choices.find(item => item.id === id); if (token !== this.formRequest) return; this.$set(this.metadata, key, meta); const score = meta.questionType === 'FILL_BLANK' ? meta.blanks.reduce((sum, blank) => sum + blank.score, 0) : kind === 'PROGRAMMING' ? 10 : 1; this.form.items.push({ kind, referenceId: id, score }); this.candidate = null; } catch (e) { if (token === this.formRequest) this.formError = errorMessage(e); } finally { if (token === this.formRequest) this.choiceLoading = false; }
    },
    addCreatedQuestion({ kind, question }) { if (!this.form || !this.dialog || this.form.items.length >= 50) return; const key = kind + ':' + question.id; if (this.form.items.some(item => item.kind + ':' + item.referenceId === key)) return; this.$set(this.metadata, key, question); this.form.items.push({ kind, referenceId: question.id, score: question.questionType === 'FILL_BLANK' ? question.blanks.reduce((sum, blank) => sum + blank.score, 0) : kind === 'PROGRAMMING' ? 10 : 1 }); this.searchChoices(''); },
    move(index, offset) { const items = this.form.items.slice(), item = items.splice(index, 1)[0]; items.splice(index + offset, 0, item); this.form.items = items; },
    async save() { if (!this.form || this.saving) return; if (!this.form.title.trim() || !this.form.items.length) { this.preview = false; this.formError = '请填写名称并添加题目'; return; } const token = this.formRequest; this.saving = true; this.formError = ''; try { const saved = responseData(await api.adminSavePaper({ ...this.form, answerReleaseAt: this.form.answerReleaseAt ? Number(this.form.answerReleaseAt) : null, explanationReleaseAt: this.form.explanationReleaseAt ? Number(this.form.explanationReleaseAt) : null })); if (token === this.formRequest) { this.dialog = false; this.$message.success('试卷已保存'); this.$emit('saved', saved); this.load(); } } catch (e) { if (token === this.formRequest) { this.preview = false; this.formError = errorMessage(e); } } finally { if (token === this.formRequest) this.saving = false; } },
  },
};
</script>
<style scoped>
.authoring-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.authoring-footer > div { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }.authoring-footer .el-button { margin: 0; }
.publication-summary { padding: 16px 20px; margin-bottom: 24px; border-left: 3px solid var(--accent); background: var(--soft); }.publication-summary p { color: var(--muted); font-size: 13px; line-height: 1.8; margin: 8px 0 0; }.publication-summary strong { overflow-wrap: anywhere; }
.paper-admin.editor-only { padding: 0; }.paper-preview ol { list-style: decimal; padding-left: 24px; }.paper-preview li { padding: 16px 0; border-bottom: 1px solid var(--line); }.paper-preview li span, .paper-preview li small { display: block; margin-top: 8px; color: var(--muted); }.preview-text { white-space: pre-wrap; }.paper-admin { padding: 24px; color: var(--text); }.admin-heading, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.admin-heading h1 { font-size: 24px; margin: 0 0 12px; }.admin-heading p, .help { color: var(--muted); line-height: 1.8; font-size: 13px; }.search { display: flex; gap: 12px; margin: 24px 0; }.search .el-input { max-width: 360px; }.question-picker { display: flex; gap: 12px; }.question-picker .el-select:nth-child(2) { flex: 1; }.selected-items { padding: 0; list-style: none; margin: 20px 0 28px; }.selected-items li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 16px 12px; border-bottom: 1px solid var(--line); background: var(--soft); }.selected-items .el-button { margin: 0; }.order { color: var(--accent); width: 24px; }.item-title { flex: 1; min-width: 180px; }.item-title strong { display: block; overflow-wrap: anywhere; }.item-title small { display: block; color: var(--muted); margin-top: 4px; }.rule-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }.rule-grid .el-select { width: 100%; }.rule-grid .el-date-editor { width: 100%; margin-top: 12px; }.el-pagination { margin-top: 24px; overflow-x: auto; }
@media(max-width:700px) { .paper-admin { padding: 12px; }.admin-heading, .question-picker { flex-wrap: wrap; }.question-picker .el-select { width: 100%; }.rule-grid { grid-template-columns: 1fr; }.item-title { min-width: calc(100% - 40px); } }
@media(max-width:420px) { .authoring-footer { align-items: flex-start; }.authoring-footer .el-button { padding: 10px; } }
</style>
<style>.paper-editor-dialog .el-dialog__body { max-height: 72vh; overflow-y: auto; }</style>
