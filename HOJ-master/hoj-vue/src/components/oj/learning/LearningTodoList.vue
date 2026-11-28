<template>
  <section class="learning-todos" aria-label="待办事项">
    <form class="todo-add" @submit.prevent="add">
      <el-input v-model="draft" size="small" aria-label="新待办" placeholder="添加一项待办" maxlength="200" />
      <el-button type="primary" size="small" native-type="submit" :disabled="!draft.trim()">添加</el-button>
    </form>

    <p v-if="!items.length" class="todo-empty">还没有待办</p>
    <ul v-else class="todo-items">
      <li v-for="item in items" :key="item.id" class="todo-row">
        <form v-if="editingId === item.id" class="todo-edit" @submit.prevent="rename" @keydown.esc.stop.prevent="cancelEdit">
          <el-input v-model="editingTitle" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" aria-label="编辑待办内容" />
          <div class="todo-actions">
            <el-button size="mini" @click="cancelEdit">取消</el-button>
            <el-button size="mini" type="primary" native-type="submit" :disabled="!editingTitle.trim()">保存</el-button>
          </div>
        </form>
        <template v-else>
          <label class="todo-task" :class="{ completed: item.completed }">
            <input type="checkbox" :checked="item.completed" @change="toggle(item, $event)" />
            <span class="todo-title">{{ item.title }}</span>
          </label>
          <div class="todo-actions">
            <button type="button" class="todo-icon" title="编辑待办" aria-label="编辑待办" @click="edit(item)">
              <i class="el-icon-edit" aria-hidden="true"></i>
            </button>
            <button type="button" class="todo-icon" title="删除待办" aria-label="删除待办" @click="remove(item.id)">
              <i class="el-icon-delete" aria-hidden="true"></i>
            </button>
          </div>
        </template>
      </li>
    </ul>
    <p v-if="!saved" class="todo-error" role="status">待办未保存，请检查浏览器的存储权限或空间。</p>
  </section>
</template>

<script>
import { readPreference, savePreference } from '@/common/appearance';

export default {
  name: 'LearningTodoList',
  props: {
    storageKey: { type: String, required: true },
    legacyPlanKey: { type: String, required: true },
  },
  data: () => ({ items: [], draft: '', editingId: '', editingTitle: '', saved: true }),
  watch: {
    storageKey: {
      immediate: true,
      handler() {
        this.draft = '';
        this.cancelEdit();
        this.load();
      },
    },
    legacyPlanKey: 'load',
  },
  mounted() { window.addEventListener('storage', this.syncStorage); },
  beforeDestroy() { window.removeEventListener('storage', this.syncStorage); },
  methods: {
    readList() {
      const stored = readPreference(this.storageKey, {});
      return {
        items: Array.isArray(stored.items) ? stored.items : [],
        importedPlans: Array.isArray(stored.importedPlans) ? stored.importedPlans : [],
      };
    },
    load() {
      const list = this.readList();
      this.items = list.items;
      this.saved = true;
      const plan = readPreference(this.legacyPlanKey, {}).plan;
      if (typeof plan !== 'string' || !plan.trim() || list.importedPlans.includes(this.legacyPlanKey)) return;

      // Record the import with the task so deleting that task does not re-import it.
      const task = { id: 'plan:' + this.legacyPlanKey, title: plan.trim(), completed: false };
      this.persist({ items: [...list.items, task], importedPlans: [...list.importedPlans, this.legacyPlanKey] });
    },
    syncStorage(event) {
      if (event.key === 'cdutetc:' + this.storageKey || event.key === null) this.items = this.readList().items;
    },
    persist(list) {
      this.saved = savePreference(this.storageKey, list);
      if (this.saved) this.items = list.items;
      return this.saved;
    },
    updateItems(update) {
      const list = this.readList();
      list.items = update(list.items);
      return this.persist(list);
    },
    add() {
      const title = this.draft.trim();
      if (!title) return;
      const createdAt = Date.now();
      const id = createdAt.toString(36) + '-' + Math.random().toString(36).slice(2);
      if (this.updateItems(items => [...items, { id, title, completed: false, createdAt }])) this.draft = '';
    },
    edit(item) {
      this.editingId = item.id;
      this.editingTitle = item.title;
      this.$nextTick(() => {
        const input = this.$el.querySelector('textarea');
        if (input) input.focus();
      });
    },
    cancelEdit() {
      this.editingId = '';
      this.editingTitle = '';
    },
    rename() {
      const title = this.editingTitle.trim();
      if (!title) return;
      const saved = this.updateItems(items => items.map(item => item.id === this.editingId ? { ...item, title } : item));
      if (saved) this.cancelEdit();
    },
    toggle(item, event) {
      const completed = event.target.checked;
      const saved = this.updateItems(items => items.map(task => task.id === item.id ? { ...task, completed } : task));
      if (!saved) event.target.checked = item.completed;
    },
    remove(id) {
      this.updateItems(items => items.filter(item => item.id !== id));
    },
  },
};
</script>

<style scoped>
.learning-todos { min-width: 0; }
.todo-add { display: flex; gap: 8px; }
.todo-add .el-input { flex: 1; min-width: 0; }
.todo-items { margin: 12px 0 0; padding: 0; list-style: none; }
.todo-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  min-height: 42px;
  padding: 5px 0;
  border-bottom: 1px solid var(--line);
}
.todo-task { display: flex; align-items: flex-start; gap: 8px; flex: 1; min-width: 0; padding: 5px 0; }
.todo-task input { flex: none; width: 16px; height: 16px; margin: 2px 0 0; accent-color: var(--accent); }
.todo-title { min-width: 0; color: var(--text); line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }
.todo-task.completed .todo-title { color: var(--muted); text-decoration: line-through; }
.todo-actions { display: flex; align-items: center; justify-content: flex-end; gap: 4px; }
.todo-actions .el-button + .el-button { margin-left: 0; }
.todo-icon {
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
}
.todo-icon:hover { color: var(--accent); background: var(--soft); }
.todo-edit { display: grid; gap: 6px; width: 100%; min-width: 0; }
.todo-empty, .todo-error { margin: 12px 0 0; color: var(--muted); font-size: 13px; }
.todo-error { color: #e15b5b; }
</style>
