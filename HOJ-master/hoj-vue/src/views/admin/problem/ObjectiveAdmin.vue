<template>
  <section class="objective-admin">
    <header class="admin-objective-heading"><div><h1>客观题与组卷</h1><p>题目可单独练习，也可加入题集或试卷；答案与解析分别控制开放时间。</p></div>
      <el-button type="primary" :disabled="saving" @click="tab === 'questions' ? newQuestion() : newSet()">{{ tab === 'questions' ? '新增客观题' : '新建题集 / 试卷' }}</el-button>
    </header>
    <el-tabs v-model="tab" @tab-click="reset"><el-tab-pane label="客观题库" name="questions" /><el-tab-pane label="题集与试卷" name="sets" /></el-tabs>
    <form class="admin-objective-filters" @submit.prevent="reset">
      <el-input v-model="keyword" maxlength="100" clearable placeholder="搜索标题" />
      <el-select v-if="tab === 'questions'" v-model="type" clearable placeholder="全部题型" @change="reset"><el-option v-for="(label, value) in questionTypes" :key="value" :value="value" :label="label" /></el-select>
      <el-button native-type="submit">搜索</el-button>
    </form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-table v-loading="loading" :data="rows" empty-text="暂时没有记录，可从右上角新建">
      <el-table-column prop="id" label="ID" width="80" /><el-table-column prop="title" label="标题" min-width="240" />
      <el-table-column label="类型" width="120"><template slot-scope="{ row }">{{ tab === 'questions' ? questionTypes[row.questionType] : modes[row.mode] }}</template></el-table-column>
      <el-table-column v-if="tab === 'sets'" prop="questionCount" label="题数" width="80" />
      <el-table-column label="发布状态" width="100"><template slot-scope="{ row }"><el-tag :type="row.published ? 'success' : 'info'">{{ row.published ? '已发布' : '未发布' }}</el-tag></template></el-table-column>
      <el-table-column v-if="tab === 'questions'" label="单题练习" width="110"><template slot-scope="{ row }">{{ row.practiceEnabled === false ? '未开放' : '允许' }}</template></el-table-column>
      <el-table-column label="操作" width="130"><template slot-scope="{ row }"><el-button type="text" @click="edit(row)">{{ tab === 'questions' ? '编辑题目' : '编辑与开放设置' }}</el-button></template></el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :current-page="page" :page-size="20" @current-change="changePage" />
    <el-dialog title="客观题编辑" :visible.sync="questionDialog" width="min(900px, 94vw)" top="5vh" custom-class="objective-editor-dialog" append-to-body :close-on-click-modal="false" :before-close="closeQuestion">
      <el-form v-if="question" label-position="top" @submit.native.prevent>
        <el-form-item label="标题"><el-input v-model="question.title" maxlength="200" show-word-limit /></el-form-item>
        <el-form-item label="题目 Rating"><el-input-number v-model="question.rating" :min="0" :max="5000" :step="100" :precision="0" placeholder="未评级" /><span class="form-help">留空为未评级</span></el-form-item>
        <el-form-item label="题目来源"><el-input v-model="question.source" maxlength="200" show-word-limit placeholder="例如：计算机二级 2025 年真题；留空显示本校题库" /></el-form-item>
        <el-form-item label="题型"><el-select v-model="question.questionType" @change="changeQuestionType"><el-option v-for="(label, value) in questionTypes" :key="value" :value="value" :label="label" /></el-select></el-form-item>
        <el-form-item label="题干"><el-input v-model="question.stem" type="textarea" :rows="6" maxlength="30000" /></el-form-item>
        <el-form-item v-if="!['TRUE_FALSE', 'FILL_BLANK'].includes(question.questionType)" label="选项（2 至 10 个）">
          <div v-for="(option, index) in question.options" :key="option.id" class="option-editor"><strong>{{ option.id }}</strong><el-input v-model="option.text" type="textarea" :rows="2" maxlength="4000" /><el-button :disabled="question.options.length <= 2" @click="removeOption(index)">移除</el-button></div>
          <el-button :disabled="question.options.length >= 10" @click="addOption">添加选项</el-button>
        </el-form-item>
        <el-form-item v-if="question.questionType === 'FILL_BLANK'" label="填空与标准答案（按顺序，每空独立计分）">
          <div v-for="(blank, index) in question.blanks" :key="blank.id" class="blank-editor">
            <strong>第 {{ index + 1 }} 空</strong>
            <el-input v-model="blank.label" maxlength="100" placeholder="填空说明，例如：最短路径长度" />
            <el-input :value="blank.answers.join('\n')" type="textarea" :rows="3" placeholder="每行一个可接受答案，最多 20 个" @input="blank.answers = $event.split('\n')" />
            <div class="blank-settings"><span>分值</span><el-input-number v-model="blank.score" :min="1" :max="1000" :precision="0" size="small" /><el-checkbox v-model="blank.trim">忽略首尾空格</el-checkbox><el-checkbox v-model="blank.caseSensitive">区分大小写</el-checkbox><el-button type="text" :disabled="question.blanks.length <= 1" @click="question.blanks.splice(index, 1)">移除此空</el-button></div>
          </div>
          <el-button :disabled="question.blanks.length >= 20" @click="addBlank">添加填空</el-button>
          <small class="form-help">支持单空与多空，题目总分为各空分值之和。等价答案由你逐项设置，系统不推断数学表达式等价。</small>
        </el-form-item>
        <el-form-item v-else label="标准答案">
          <el-checkbox-group v-if="question.questionType === 'MULTIPLE_CHOICE'" v-model="question.correctAnswers"><el-checkbox v-for="option in question.options" :key="option.id" :label="option.id">{{ option.id }}</el-checkbox></el-checkbox-group>
          <el-radio-group v-else :value="question.correctAnswers[0] || ''" @input="$set(question, 'correctAnswers', [$event])"><el-radio v-for="option in answerOptions" :key="option.id" :label="option.id">{{ option.id }}{{ question.questionType === 'TRUE_FALSE' ? '（' + option.text + '）' : '' }}</el-radio></el-radio-group>
          <small v-if="question.questionType === 'MULTIPLE_CHOICE'" class="form-help">多选全部选对得 1 分，少选、多选、错选均为 0 分。</small>
        </el-form-item>
        <el-form-item label="解析"><el-input v-model="question.explanation" type="textarea" :rows="5" maxlength="30000" /></el-form-item>
        <el-form-item label="知识点"><el-select v-model="question.tagIds" multiple filterable placeholder="选择已有公共题目标签"><el-option v-for="tag in tags" :key="tag.id" :value="tag.id" :label="tag.name" /></el-select></el-form-item>
        <el-form-item label="发布"><el-switch v-model="question.published" /><small class="form-help">发布后，题目可按单题练习设置开放，也可加入已发布题集。每次保存创建新版本，已有作答保留原题。</small></el-form-item>
        <el-form-item label="允许单题练习"><el-switch v-model="question.practiceEnabled" /><small class="form-help">开启并发布后，学生可在客观题页直接练习本题。仅用于试卷的题目请关闭，避免学生通过单题练习提前查看答案。</small></el-form-item>
        <template v-if="question.practiceEnabled">
          <div class="release-fields">
            <el-form-item label="单题练习答案与判分反馈开放条件"><el-select v-model="question.answerPolicy"><el-option v-for="(label, value) in releasePolicies" :key="value" :value="value" :label="label" /></el-select><el-date-picker v-if="question.answerPolicy === 'AT_TIME'" v-model="question.answerReleaseAt" type="datetime" value-format="timestamp" placeholder="选择答案开放时间" /></el-form-item>
            <el-form-item label="单题练习解析开放条件"><el-select v-model="question.explanationPolicy"><el-option v-for="(label, value) in releasePolicies" :key="value" :value="value" :label="label" /></el-select><el-date-picker v-if="question.explanationPolicy === 'AT_TIME'" v-model="question.explanationReleaseAt" type="datetime" value-format="timestamp" placeholder="选择解析开放时间" /></el-form-item>
          </div>
          <p class="form-help">单题提交即完成本次练习；提交后和交卷后两种条件在此场景下同时满足。加入题集或试卷时，以题集或试卷的开放设置为准。</p>
        </template>
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      </el-form>
      <span slot="footer"><el-button :disabled="saving" @click="closeQuestion()">取消</el-button><el-button type="primary" :loading="saving" @click="saveQuestion">保存题目</el-button></span>
    </el-dialog>
    <el-dialog title="题集 / 试卷与开放设置" :visible.sync="setDialog" width="min(900px, 94vw)" top="5vh" custom-class="objective-editor-dialog" append-to-body :close-on-click-modal="false" :before-close="closeSet">
      <el-form v-if="problemSet" label-position="top" @submit.native.prevent>
        <el-form-item label="名称"><el-input v-model="problemSet.title" maxlength="200" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="problemSet.description" type="textarea" :rows="3" maxlength="5000" /></el-form-item>
        <el-form-item label="答题模式"><el-select v-model="problemSet.mode" :disabled="!!problemSet.id" @change="changeMode"><el-option v-for="(label, value) in modes" :key="value" :value="value" :label="label" /></el-select><small class="form-help">逐题练习按题提交；整卷答题可保存修改，交卷后统一判分。比赛另设规则。</small></el-form-item>
        <el-form-item label="选择题目（最多 50 题，按下方顺序出题）">
          <el-select v-model="problemSet.questionIds" multiple filterable remote :remote-method="searchChoices" :loading="choiceLoading" placeholder="输入标题搜索自己的题目" style="width:100%">
            <el-option v-for="item in choices" :key="item.id" :value="item.id" :label="item.id + '. ' + item.title + (item.published ? '' : '（未发布）')" />
          </el-select>
          <ol class="selected-questions"><li v-for="(id, index) in problemSet.questionIds" :key="id"><span>{{ choiceTitle(id) }}</span><el-button size="mini" :disabled="index === 0" aria-label="上移题目" @click="moveQuestion(index, -1)">上移</el-button><el-button size="mini" :disabled="index === problemSet.questionIds.length - 1" aria-label="下移题目" @click="moveQuestion(index, 1)">下移</el-button></li></ol>
        </el-form-item>
        <div class="release-fields">
          <el-form-item label="答案与判分反馈开放条件"><el-select v-model="problemSet.answerPolicy"><el-option v-for="(label, value) in releasePolicies" :key="value" :value="value" :label="label" /></el-select><el-date-picker v-if="problemSet.answerPolicy === 'AT_TIME'" v-model="problemSet.answerReleaseAt" type="datetime" value-format="timestamp" placeholder="选择答案开放时间" /></el-form-item>
          <el-form-item label="解析开放条件"><el-select v-model="problemSet.explanationPolicy"><el-option v-for="(label, value) in releasePolicies" :key="value" :value="value" :label="label" /></el-select><el-date-picker v-if="problemSet.explanationPolicy === 'AT_TIME'" v-model="problemSet.explanationReleaseAt" type="datetime" value-format="timestamp" placeholder="选择解析开放时间" /></el-form-item>
        </div>
        <p class="form-help">整卷交卷前仅保存草稿，即使选择“提交本题后”，也要交卷后才开放。指定时间开放同样要求已提交。解析可能包含答案，请按实际需要分别设置。修改开放条件会同时影响已有作答；下架题集会暂停学生访问。</p>
        <el-form-item label="发布"><el-switch v-model="problemSet.published" /></el-form-item>
        <el-alert v-if="formError" :title="formError" type="error" :closable="false" />
      </el-form>
      <span slot="footer"><el-button :disabled="saving" @click="closeSet()">取消</el-button><el-button type="primary" :loading="saving" @click="saveSet">保存题集与规则</el-button></span>
    </el-dialog>
  </section>
</template>
<script>
import api from '@/common/api';
import { modes, questionTypes, releasePolicies, responseData, errorMessage } from '@/common/objective';
const options = () => [{ id: 'A', text: '' }, { id: 'B', text: '' }];
export default {
  name: 'ObjectiveAdmin',
  data: () => ({
    tab: 'questions', keyword: '', type: '', page: 1, total: 0, rows: [], loading: false, saving: false, error: '', formError: '',
    question: null, problemSet: null, questionDialog: false, setDialog: false, tags: [], choices: [], choiceLoading: false,
    request: 0, choiceRequest: 0, formRequest: 0, modes, questionTypes, releasePolicies,
  }),
  computed: {
    uid() { return this.$store.getters.userInfo.uid; },
    answerOptions() { return this.question && this.question.questionType === 'TRUE_FALSE' ? [{ id: 'TRUE', text: '正确' }, { id: 'FALSE', text: '错误' }] : this.question ? this.question.options : []; },
  },
  watch: { uid() { this.request++; this.formRequest++; this.choiceRequest++; this.rows = []; this.question = null; this.problemSet = null; this.questionDialog = false; this.setDialog = false; this.choices = []; this.saving = false; this.reset(); } },
  created() { this.load(); api.getProblemTagList('All').then(response => { this.tags = responseData(response) || []; }).catch(() => {}); },
  beforeDestroy() { this.request++; this.formRequest++; this.choiceRequest++; },
  methods: {
    reset() { this.page = 1; this.load(); },
    changePage(page) { this.page = page; this.load(); },
    async load() {
      const request = ++this.request;
      this.loading = true; this.error = ''; this.rows = [];
      try {
        const params = { currentPage: this.page, limit: 20, keyword: this.keyword, questionType: this.type };
        const result = responseData(await (this.tab === 'questions' ? api.adminObjectiveQuestions(params) : api.adminObjectiveSets(params)));
        if (request === this.request) { this.rows = result.records; this.total = result.total; }
      } catch (error) { if (request === this.request) this.error = errorMessage(error); }
      finally { if (request === this.request) this.loading = false; }
    },
    newQuestion() {
      if (this.saving) return;
      this.formRequest++; this.formError = '';
      this.question = { title: '', source: '', questionType: 'SINGLE_CHOICE', stem: '', options: options(), blanks: [], correctAnswers: [], explanation: '', tagIds: [], published: false, practiceEnabled: true, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER', answerReleaseAt: null, explanationReleaseAt: null };
      this.questionDialog = true;
    },
    newSet() {
      if (this.saving) return;
      this.formRequest++; this.formError = '';
      this.problemSet = { title: '', description: '', mode: 'PRACTICE', questionIds: [], answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER', answerReleaseAt: null, explanationReleaseAt: null, published: false };
      this.setDialog = true; this.searchChoices('');
    },
    async edit(row) {
      if (this.saving) return;
      const request = ++this.formRequest; this.error = ''; this.formError = '';
      try {
        if (this.tab === 'questions') {
          const question = responseData(await api.adminObjectiveQuestion(row.id));
          if (request !== this.formRequest) return;
          this.question = { practiceEnabled: true, answerPolicy: 'AFTER_ANSWER', explanationPolicy: 'AFTER_ANSWER', answerReleaseAt: null, explanationReleaseAt: null, ...question }; this.questionDialog = true;
        } else {
          const set = responseData(await api.adminObjectiveSet(row.id));
          const selected = await Promise.all(set.questionIds.map(id => api.adminObjectiveQuestion(id).then(responseData)));
          if (request !== this.formRequest) return;
          this.problemSet = set; this.choices = selected; this.setDialog = true; this.searchChoices('');
        }
      } catch (error) { if (request === this.formRequest) this.error = errorMessage(error); }
    },
    closeQuestion(done) { if (this.saving) return; this.questionDialog = false; this.formRequest++; if (typeof done === 'function') done(); },
    closeSet(done) { if (this.saving) return; this.setDialog = false; this.formRequest++; if (typeof done === 'function') done(); },
    changeQuestionType() {
      if (this.question.options.some(option => ['TRUE', 'FALSE'].includes(option.id))) this.question.options = options();
      if (this.question.options.length < 2) this.question.options = options();
      if (this.question.questionType === 'FILL_BLANK' && !(this.question.blanks || []).length) { this.$set(this.question, 'blanks', []); this.addBlank(); }
      this.question.correctAnswers = [];
    },
    addBlank() { this.question.blanks.push({ id: 'blank_' + Date.now() + '_' + this.question.blanks.length, label: '', answers: [''], score: 1, trim: true, caseSensitive: true }); },
    addOption() { this.question.options.push({ id: String.fromCharCode(65 + this.question.options.length), text: '' }); },
    removeOption(index) { this.question.options.splice(index, 1); this.question.options.forEach((option, i) => { option.id = String.fromCharCode(65 + i); }); this.question.correctAnswers = []; },
    changeMode() { const policy = this.problemSet.mode === 'PAPER' ? 'AFTER_FINISH' : 'AFTER_ANSWER'; this.problemSet.answerPolicy = policy; this.problemSet.explanationPolicy = policy; },
    choiceTitle(id) { const choice = this.choices.find(item => item.id === id); return choice ? choice.title : '题目 ' + id; },
    moveQuestion(index, offset) { const ids = this.problemSet.questionIds.slice(); const moved = ids.splice(index, 1)[0]; ids.splice(index + offset, 0, moved); this.problemSet.questionIds = ids; },
    async searchChoices(keyword) {
      const request = ++this.choiceRequest; this.choiceLoading = true;
      try {
        const result = responseData(await api.adminObjectiveQuestions({ limit: 100, keyword }));
        if (request !== this.choiceRequest) return;
        const selectedIds = this.problemSet ? this.problemSet.questionIds : [];
        const selected = this.choices.filter(item => selectedIds.includes(item.id));
        this.choices = Array.from(new Map([...selected, ...result.records].map(item => [item.id, item])).values());
      } catch (error) { if (request === this.choiceRequest) this.formError = errorMessage(error); }
      finally { if (request === this.choiceRequest) this.choiceLoading = false; }
    },
    async saveQuestion() {
      if (this.saving) return;
      const blank = this.question.questionType === 'FILL_BLANK';
      if (!this.question.title.trim() || !this.question.stem.trim() || (blank ? !this.question.blanks.length || this.question.blanks.some(item => !item.answers.length || item.answers.some(answer => !answer.trim())) : !this.question.correctAnswers.length)) { this.formError = '请填写标题、题干和各项标准答案。'; return; }
      this.saving = true; this.formError = ''; const request = this.formRequest;
      try {
        const payload = { ...this.question, answerReleaseAt: this.question.answerReleaseAt ? Number(this.question.answerReleaseAt) : null, explanationReleaseAt: this.question.explanationReleaseAt ? Number(this.question.explanationReleaseAt) : null };
        await api.adminSaveObjectiveQuestion(payload);
        if (request !== this.formRequest) return;
        this.questionDialog = false; this.$message.success('题目已保存'); this.load();
      } catch (error) { if (request === this.formRequest) this.formError = errorMessage(error); }
      finally { if (request === this.formRequest) this.saving = false; }
    },
    async saveSet() {
      if (this.saving) return;
      if (!this.problemSet.title.trim() || !this.problemSet.questionIds.length) { this.formError = '请填写名称并选择题目。'; return; }
      this.saving = true; this.formError = ''; const request = this.formRequest;
      try {
        const payload = { ...this.problemSet, answerReleaseAt: this.problemSet.answerReleaseAt ? Number(this.problemSet.answerReleaseAt) : null, explanationReleaseAt: this.problemSet.explanationReleaseAt ? Number(this.problemSet.explanationReleaseAt) : null };
        await api.adminSaveObjectiveSet(payload);
        if (request !== this.formRequest) return;
        this.setDialog = false; this.$message.success('题集与开放规则已保存'); this.load();
      } catch (error) { if (request === this.formRequest) this.formError = errorMessage(error); }
      finally { if (request === this.formRequest) this.saving = false; }
    },
  },
};
</script>
<style scoped>
.objective-admin { padding: 24px; color: var(--text); }
.admin-objective-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.admin-objective-heading h1 { font-size: 24px; margin: 0 0 12px; }
.admin-objective-heading p, .form-help { color: var(--muted); line-height: 1.8; }
.admin-objective-filters { display: flex; gap: 12px; margin-bottom: 20px; }
.admin-objective-filters .el-input { max-width: 360px; }
.option-editor { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 12px; }
.option-editor strong { padding-top: 8px; }
.blank-editor { display: grid; gap: 12px; background: var(--soft); border: 1px solid var(--line); border-radius: 10px; padding: 16px; margin-bottom: 16px; }
.blank-settings { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.form-help { display: block; margin-top: 8px; }
.release-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.release-fields .el-date-editor { margin-top: 12px; width: 100%; }
.selected-questions li { display: flex; align-items: center; gap: 8px; padding: 6px 0; }
.selected-questions li > span { flex: 1; }
.selected-questions { padding-left: 0; }
.el-pagination { margin-top: 24px; overflow-x: auto; }
@media (max-width: 700px) { .objective-admin { padding: 12px; } .admin-objective-heading, .admin-objective-filters { flex-wrap: wrap; } .release-fields { grid-template-columns: 1fr; } .option-editor { gap: 6px; } }
</style>
<style>
.objective-editor-dialog .el-dialog__body { max-height: 70vh; overflow-y: auto; }
</style>
