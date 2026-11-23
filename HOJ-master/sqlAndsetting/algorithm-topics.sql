-- XCPC common learning taxonomy v1 (project-maintained; OI Wiki topic navigation reference).
-- Additive and repeatable. Existing exact-name global tags are reused, existing custom tags stay available.
CREATE TABLE IF NOT EXISTS algorithm_topic (
 code VARCHAR(80) NOT NULL PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 group_code VARCHAR(40) NOT NULL,
 group_name VARCHAR(80) NOT NULL,
 sort_order INT NOT NULL,
 problem_tag_id BIGINT UNSIGNED DEFAULT NULL,
 training_category_id BIGINT UNSIGNED DEFAULT NULL,
 tag_classification_id BIGINT UNSIGNED DEFAULT NULL,
 UNIQUE KEY uq_algorithm_problem_tag(problem_tag_id),
 UNIQUE KEY uq_algorithm_training_category(training_category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO algorithm_topic(code,name,group_code,group_name,sort_order) VALUES
('basics.general','算法基础','basics','基础算法',0),
('basics.complexity','复杂度分析','basics','基础算法',1),
('basics.simulation','模拟','basics','基础算法',2),
('basics.enumeration','枚举','basics','基础算法',3),
('basics.sorting','排序','basics','基础算法',4),
('basics.binary-search','二分','basics','基础算法',5),
('basics.prefix-sum','前缀和与差分','basics','基础算法',6),
('basics.two-pointers','双指针','basics','基础算法',7),
('basics.greedy','贪心','basics','基础算法',8),
('basics.divide-conquer','分治','basics','基础算法',9),
('basics.constructive','构造','basics','基础算法',10),
('basics.bitmask','位运算','basics','基础算法',11),
('basics.randomized','随机化','basics','基础算法',12),
('basics.meet-in-middle','折半搜索','basics','基础算法',13),
('search.general','搜索基础','search','搜索',100),
('search.dfs','深度优先搜索','search','搜索',101),
('search.bfs','广度优先搜索','search','搜索',102),
('search.backtracking','回溯与剪枝','search','搜索',103),
('search.bidirectional','双向搜索','search','搜索',104),
('search.iterative','迭代加深','search','搜索',105),
('search.astar','启发式搜索','search','搜索',106),
('search.dancing-links','精确覆盖与舞蹈链','search','搜索',107),
('dp.general','动态规划基础','dp','动态规划',200),
('dp.knapsack','背包 DP','dp','动态规划',201),
('dp.linear','线性 DP','dp','动态规划',202),
('dp.interval','区间 DP','dp','动态规划',203),
('dp.tree','树形 DP','dp','动态规划',204),
('dp.bitmask','状态压缩 DP','dp','动态规划',205),
('dp.digit','数位 DP','dp','动态规划',206),
('dp.probability','概率与期望 DP','dp','动态规划',207),
('dp.counting','计数 DP','dp','动态规划',208),
('dp.dag','DAG 上的 DP','dp','动态规划',209),
('dp.optimization','DP 优化','dp','动态规划',210),
('data-structure.general','数据结构基础','data-structure','数据结构',300),
('data-structure.stack-queue','栈与队列','data-structure','数据结构',301),
('data-structure.heap','堆与优先队列','data-structure','数据结构',302),
('data-structure.dsu','并查集','data-structure','数据结构',303),
('data-structure.fenwick','树状数组','data-structure','数据结构',304),
('data-structure.segment-tree','线段树','data-structure','数据结构',305),
('data-structure.sparse-table','ST 表','data-structure','数据结构',306),
('data-structure.balanced-tree','平衡树','data-structure','数据结构',307),
('data-structure.persistent','可持久化数据结构','data-structure','数据结构',308),
('data-structure.hld','树链剖分','data-structure','数据结构',309),
('data-structure.lct','动态树','data-structure','数据结构',310),
('data-structure.mo','莫队算法','data-structure','数据结构',311),
('data-structure.block','分块','data-structure','数据结构',312),
('graph.general','图论基础','graph','图论',400),
('graph.traversal','图的遍历','graph','图论',401),
('graph.trees','树与倍增','graph','图论',402),
('graph.shortest-path','最短路','graph','图论',403),
('graph.mst','最小生成树','graph','图论',404),
('graph.topological','拓扑排序','graph','图论',405),
('graph.scc','强连通分量','graph','图论',406),
('graph.biconnected','双连通分量','graph','图论',407),
('graph.matching','二分图匹配','graph','图论',408),
('graph.flow','网络流','graph','图论',409),
('graph.lca','最近公共祖先','graph','图论',410),
('graph.sat','2-SAT','graph','图论',411),
('graph.euler','欧拉图','graph','图论',412),
('graph.centroid','点分治','graph','图论',413),
('graph.virtual-tree','虚树','graph','图论',414),
('math.general','数学基础','math','数学',500),
('math.number-theory','数论','math','数学',501),
('math.gcd','最大公约数与扩展欧几里得','math','数学',502),
('math.sieve','素数与筛法','math','数学',503),
('math.modular','模运算与逆元','math','数学',504),
('math.combinatorics','组合数学','math','数学',505),
('math.probability','概率与期望','math','数学',506),
('math.game','博弈论','math','数学',507),
('math.linear-algebra','线性代数','math','数学',508),
('math.matrix','矩阵与快速幂','math','数学',509),
('math.polynomial','多项式与 FFT/NTT','math','数学',510),
('math.mobius','莫比乌斯反演','math','数学',511),
('math.crt','中国剩余定理','math','数学',512),
('math.numerical','数值计算','math','数学',513),
('math.integration','积分与数值积分','math','数学',514),
('string.general','字符串基础','string','字符串',600),
('string.hash','字符串哈希','string','字符串',601),
('string.kmp','KMP','string','字符串',602),
('string.z-function','Z 函数','string','字符串',603),
('string.trie','字典树','string','字符串',604),
('string.aho-corasick','AC 自动机','string','字符串',605),
('string.suffix-array','后缀数组','string','字符串',606),
('string.suffix-automaton','后缀自动机','string','字符串',607),
('string.palindrome','回文算法','string','字符串',608),
('string.lyndon','Lyndon 分解','string','字符串',609),
('geometry.general','计算几何基础','geometry','计算几何',700),
('geometry.vectors','向量与点线位置关系','geometry','计算几何',701),
('geometry.convex-hull','凸包','geometry','计算几何',702),
('geometry.intersection','扫描线与几何交','geometry','计算几何',703),
('geometry.half-plane','半平面交','geometry','计算几何',704),
('geometry.circle','圆与球','geometry','计算几何',705),
('geometry.rotating-calipers','旋转卡壳','geometry','计算几何',706),
('geometry.closest-pair','平面最近点对','geometry','计算几何',707)
ON DUPLICATE KEY UPDATE name=VALUES(name),group_code=VALUES(group_code),group_name=VALUES(group_name),sort_order=VALUES(sort_order);

START TRANSACTION;
INSERT INTO tag_classification(name,oj,`rank`)
 SELECT a.group_name,'ME',MIN(a.sort_order) FROM algorithm_topic a
 WHERE NOT EXISTS(SELECT 1 FROM tag_classification c WHERE c.name=a.group_name AND c.oj='ME')
 GROUP BY a.group_code,a.group_name;
UPDATE algorithm_topic a SET tag_classification_id=(SELECT MIN(c.id) FROM tag_classification c WHERE c.name=a.group_name AND c.oj='ME');
INSERT INTO tag(name,color,oj,tcid)
 SELECT a.name,'#217e9d','ME',a.tag_classification_id FROM algorithm_topic a
 WHERE NOT EXISTS(SELECT 1 FROM tag t WHERE t.name=a.name AND t.oj='ME' AND t.gid IS NULL);
UPDATE algorithm_topic a SET problem_tag_id=(SELECT MIN(t.id) FROM tag t WHERE t.name=a.name AND t.oj='ME' AND t.gid IS NULL);
UPDATE tag t JOIN algorithm_topic a ON a.problem_tag_id=t.id SET t.tcid=a.tag_classification_id;
INSERT INTO training_category(name,color)
 SELECT a.name,'#217e9d' FROM algorithm_topic a
 WHERE NOT EXISTS(SELECT 1 FROM training_category c WHERE c.name=a.name AND c.gid IS NULL);
UPDATE algorithm_topic a SET training_category_id=(SELECT MIN(c.id) FROM training_category c WHERE c.name=a.name AND c.gid IS NULL);
COMMIT;
