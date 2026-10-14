const assert = require('assert').strict;
const fixtures = require('./collection-fixtures.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

module.exports = async function reviewCollections({ ev, cdp, route, shot, component }) {
  const checks = { pages: [], interactions: {} };
  const paths = ['/home', '/training', '/contest', '/discussion', '/group'];
  const viewport = async (width, height = 1080) => {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false });
    await sleep(180);
  };
  const bounds = () => ev(`(()=>{
    const page=document.querySelector('.collection-page'),cards=[...page.querySelectorAll('.collection-card')],firstRow=cards.filter(x=>Math.abs(x.offsetTop-cards[0].offsetTop)<2);
    return {overflow:document.documentElement.scrollWidth>innerWidth,cardCount:cards.length,columns:firstRow.length,
      equalRow:firstRow.every(x=>Math.abs(x.offsetHeight-firstRow[0].offsetHeight)<2),
      escaped:cards.some(x=>x.scrollWidth>x.clientWidth+1),
      photo:!!page.querySelector('.xcpc-placeholder'),homeCards:page.querySelectorAll('.home-grid > .el-card').length,
      footerMismatch:cards.some(card=>{const footer=card.querySelector('.collection-card-footer');if(!footer)return false;const bg=getComputedStyle(footer).backgroundColor;return bg!=='rgba(0, 0, 0, 0)'&&bg!==getComputedStyle(card).backgroundColor}),
      background:cards[0]&&getComputedStyle(cards[0]).backgroundColor};})()`);
  for (const theme of ['light', 'dark']) {
    await ev(`${component('AppearanceSettings')}.set('theme',${JSON.stringify(theme)})`);
    for (const width of [1440, 390]) {
      await viewport(width, width === 390 ? 844 : 1080);
      for (const path of paths) {
        await route(path);
        const result = { path, theme, width, ...(await bounds()) };
        checks.pages.push(result);
        await shot('cards-' + path.slice(1) + '-' + theme + '-' + width);
        assert(!result.overflow && !result.escaped, 'card overflow ' + JSON.stringify(result));
        assert(result.equalRow, 'unequal cards ' + JSON.stringify(result));
        assert(!result.footerMismatch, 'card footer theme mismatch ' + JSON.stringify(result));
        if (['/training', '/contest', '/group'].includes(path)) assert.equal(result.columns, width === 1440 ? 3 : 1);
        if (path === '/home') {
          assert(result.photo); assert.equal(result.homeCards, 7);
          if (width === 1440) await shot('cards-home-' + theme + '-full', null, true);
        }
      }
    }
  }
  await viewport(900);
  await route('/training');
  checks.tablet = await bounds();
  assert.equal(checks.tablet.columns, 2);
  assert(!checks.tablet.overflow);
  await viewport(1440);
  await ev(`${component('AppearanceSettings')}.set('theme','light')`);

  await ev("document.querySelector('.training-page .btn-next').click()");
  await sleep(700);
  checks.interactions.trainingPage = await ev("location.search");
  assert(checks.interactions.trainingPage.includes('currentPage=2'));
  await ev(`document.querySelectorAll('.training-page .collection-filter-row')[0].querySelectorAll('button')[1].click()`);
  await sleep(700);
  checks.interactions.trainingFilter = await ev("location.search");
  assert(checks.interactions.trainingFilter.includes('auth=Public') && checks.interactions.trainingFilter.includes('currentPage=1'));
  await route('/training?keyword=没有这样的题集');
  checks.interactions.empty = await ev("!!document.querySelector('.collection-feedback') && !document.querySelector('.collection-card')");
  assert(checks.interactions.empty);

  fixtures.failed.add('/api/get-training-list');
  await route('/training?keyword=失败场景');
  checks.interactions.failure = await ev("document.querySelector('.collection-feedback')?.innerText");
  assert(checks.interactions.failure.includes('重新加载'));
  fixtures.failed.clear();
  await ev(`${component('TrainingList')}.query.keyword='';document.querySelector('.collection-feedback button').click()`);
  await sleep(650);
  assert(await ev("!!document.querySelector('.training-card')"));

  // Long content must stay inside the same card grid, including unbroken text.
  await ev(`${component('TrainingList')}.trainingList[0].title='很长的训练标题'.repeat(35);${component('TrainingList')}.trainingList[0].categoryName='LongCategory'.repeat(12)`);
  await sleep(100);
  checks.longTitle = await bounds();
  assert(!checks.longTitle.escaped && !checks.longTitle.overflow);
  await shot('cards-long-title');

  // Keep the existing guest login gate; changing presentation must not bypass it.
  await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','')");
  for (const path of ['/training', '/contest', '/group']) {
    await route(path);
    await ev("document.querySelector('.collection-title').click()");
    await sleep(220);
    assert(await ev(`${component('NavBar')}.modalStatus.visible`), 'missing login gate ' + path);
    await ev(`${component('NavBar')}.changeModalStatus({visible:false})`);
  }
  await ev("document.getElementById('app').__vue__.$store.commit('changeUserToken','ui-fixture-token')");
  await route('/group?currentPage=2');
  checks.interactions.groupPageTypes = await ev(`({page:typeof ${component('GroupList')}.query.currentPage,limit:typeof ${component('GroupList')}.query.limit})`);
  assert.deepEqual(checks.interactions.groupPageTypes, {page:'number',limit:'number'});
  await ev("document.querySelectorAll('.group-page .filter-chip')[1].click()");
  await sleep(600);
  checks.interactions.groupFilter = await ev("location.search");
  assert(checks.interactions.groupFilter.includes('currentPage=1') && checks.interactions.groupFilter.includes('auth=1'));
  await ev("document.querySelector('.group-page .collection-actions .el-button--primary').click()");
  await sleep(250);
  assert(await ev(`${component('GroupList')}.showEditGroupDialog`));
  await ev(`${component('GroupList')}.showEditGroupDialog=false`);

  await route('/contest');
  await ev("[...document.querySelectorAll('.contest-page .filter-chip')].find(x=>x.textContent==='进行中').click()");
  await sleep(600);
  checks.interactions.contestFilter = await ev("location.search");
  assert(checks.interactions.contestFilter.includes('status=0'));
  await route('/discussion');
  await ev("document.querySelector('.discussion-page .category-item a').click()");
  await sleep(650);
  checks.interactions.discussionFilter = await ev("location.search");
  assert(checks.interactions.discussionFilter.includes('cid=1'));
  checks.requests = fixtures.seen.length;
  await ev(`${component('AppearanceSettings')}.set('theme','dark')`);
  return checks;
};
