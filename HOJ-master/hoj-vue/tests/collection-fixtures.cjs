// UI-only data for collection pages; never sent to a real backend.
const failed = new Set();
const seen = [];
const categories = [{ id: 1, name: '算法基础', color: '#217c9e' }, { id: 2, name: '图论', color: '#528a73' }, { id: 3, name: '动态规划', color: '#9b799c' }];
const trainingNames = ['语法与输入输出', '图的遍历与最短路', '动态规划：从状态定义到转移', '二分查找与双指针', '树与数据结构专题', '字符串算法与复杂度分析'];
const trainings = Array.from({ length: 22 }, (_, i) => ({ id: i + 1, rank: i + 1, title: trainingNames[i % 6], auth: i % 2 ? 'Private' : 'Public', categoryId: i % 3 + 1, categoryName: categories[i % 3].name, categoryColor: categories[i % 3].color, problemCount: 16 + i, acCount: i % 9, author: '集训队教练', gmtModified: '2026-10-14 00:00:00' }));
const contests = Array.from({ length: 6 }, (_, i) => ({ id: i + 1, title: ['秋季集训 · 周末练习赛', '新生程序设计入门赛', 'XCPC 历年题目重现练习', '图论专题挑战', '校队选拔练习', '动态规划专项赛'][i], type: i % 2, status: [0, -1, 1][i % 3], auth: i % 3, startTime: '2026-10-17 05:00:00', endTime: '2026-10-17 10:00:00', count: 24 + i * 8, openRank: true, oiRankScoreType: 'Highest' }));
const groups = Array.from({ length: 18 }, (_, i) => ({ id: i + 1, name: ['算法竞赛集训队', '图论学习小组', '新生程序设计训练营', '动态规划专题组', '数据结构与算法', '周末补题小队'][i % 6], shortName: ['集训', '图论', '入门', 'DP', '算法', '补题'][i % 6], auth: i % 3 + 1, brief: ['一起准备 XCPC，定期训练、模拟比赛和赛后复盘。', '从 BFS 与 DFS 开始，理解路径、连通性与网络流。', '从第一段程序开始，分享问题与每一点进步。'][i % 3], owner: '教练', memberCount: 12 + i, visible: i !== 5, gmtCreate: '2026-09-01 00:00:00' }));
const discussions = Array.from({ length: 13 }, (_, i) => ({ id: i + 1, title: ['关于二分查找边界的一些思考', '图的遍历：如何选择 BFS 和 DFS', '一次动态规划练习的复盘', '分享我的赛后补题流程'][i % 4], description: '先明确区间的含义，再检查每次更新是否缩小了搜索范围。欢迎分享你的思路和遇到的边界情况。', uid: i === 0 ? 'ui-test' : 'other-user', author: i === 0 ? 'UI验收账号' : '队友', role: 'user', avatar: '', categoryId: i % 3 + 1, commentNum: 3 + i, likeNum: i + 1, viewNum: 60 + i, topPriority: i === 0, gmtCreate: '2026-10-13 00:00:00' }));
function page(items, url) {
  const keyword = url.searchParams.get('keyword');
  if (keyword) items = items.filter(x => (x.title || x.name).includes(keyword));
  const auth = url.searchParams.get('auth');
  if (auth) items = items.filter(x => String(x.auth) === auth);
  const category = url.searchParams.get('categoryId') || url.searchParams.get('cid');
  if (category) items = items.filter(x => String(x.categoryId) === category);
  for (const key of ['status', 'type']) {
    const value = url.searchParams.get(key);
    if (value !== null && value !== '') items = items.filter(x => String(x[key]) === value);
  }
  const limit = Number(url.searchParams.get('limit') || 15);
  const start = (Number(url.searchParams.get('currentPage') || 1) - 1) * limit;
  return { records: items.slice(start, start + limit), total: items.length };
}
function fixture(req) {
  const url = new URL(req.url);
  const lists = { '/api/get-training-list': trainings, '/api/get-contest-list': contests, '/api/get-group-list': groups, '/api/get-discussion-list': discussions };
  if (lists[url.pathname]) { seen.push(url.href); return page(lists[url.pathname], url); }
  if (url.pathname === '/api/get-training-category' || url.pathname === '/api/discussion-category') return categories;
  if (url.pathname === '/api/get-recent-contest') return contests.slice(0, 2);
}
module.exports = { fixture, failed, seen };
