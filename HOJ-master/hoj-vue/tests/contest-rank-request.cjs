const assert = require('assert').strict;
const fs = require('fs');
const vm = require('vm');
const path = require('path');
const source = fs.readFileSync(path.join(__dirname, '../src/views/oj/contest/children/contestRankMixin.js'), 'utf8').replace(/^import .*\r?\n/gm, '').replace('export default', 'module.exports =');
let requests;
const context = { module: { exports: {} }, api: { getContestRank: () => new Promise((resolve, reject) => requests.push({ resolve, reject })) }, mapGetters: () => ({}), mapState: () => ({}), clearInterval };
vm.runInNewContext(source, context);
const mixin = context.module.exports, reply = total => ({ data: { data: { total, records: [{ uid: 'user' }] } } });
function instance() {
  requests = []; const calls = [];
  return Object.assign({ rankRequestGeneration: 0, showChart: true, $route: { params: { contestID: '6' } }, $refs: { chart: { showLoading: () => calls.push('show'), hideLoading: () => calls.push('hide') } }, applyToChart: () => calls.push('chart'), applyToTable: () => calls.push('table'), calls }, mixin.methods);
}
(async () => {
  let component = instance(), pending = component.getContestRankData(); mixin.beforeDestroy.call(component); delete component.$refs.chart;
  requests[0].resolve(reply(3)); await pending; assert.deepEqual(component.calls, ['show']); assert.equal(component.total, undefined);
  component = instance(); const old = component.getContestRankData(), latest = component.getContestRankData();
  requests[1].resolve(reply(7)); await latest; requests[0].resolve(reply(2)); await old; assert.equal(component.total, 7); assert.equal(component.calls.filter(call => call === 'table').length, 1);
  component = instance(); pending = component.getContestRankData(); component.$route.params.contestID = '8'; requests[0].resolve(reply(3)); await pending; assert.equal(component.total, undefined);
  component = instance(); pending = component.getContestRankData(); requests[0].reject(Error('offline')); await pending; assert.deepEqual(component.calls, ['show', 'hide']);
  console.log('Contest rank requests: 4 lifecycle, ordering, route and failure cases passed.');
})().catch(error => { console.error(error); process.exitCode = 1; });
