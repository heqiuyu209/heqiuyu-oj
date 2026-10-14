// Isolated UI regression checks. ALL /api/* calls are fulfilled with fixtures.
// Requires a local development server, Node 22 and Chrome; see ../README.md.
const fs = require("fs"),
  path = require("path"),
  os = require("os"),
  { spawn } = require("child_process");
const assert = require("assert").strict;
const root =
    process.env.UI_REVIEW_OUTPUT || path.join(os.tmpdir(), "cdutetc-ui-review"),
  profile = fs.mkdtempSync(path.join(os.tmpdir(), "cdutetc-source-"));
fs.mkdirSync(root, { recursive: true });
const browser = spawn(
  process.env.CHROME_PATH ||
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
  [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--disable-extensions",
    "--remote-debugging-port=0",
    "--user-data-dir=" + profile,
    "about:blank",
  ],
  { windowsHide: true, stdio: ["ignore", "ignore", "pipe"] }
);
const watchdog = setTimeout(() => {
  console.error("UI review timeout");
  browser.kill();
  process.exit(1);
}, 180000);
let stderr = "";
browser.stderr.on("data", (b) => (stderr += b));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let role = "user",
  objectiveType = "",
  delayedSubmission = false;
const requests = [];
const languages = ["C", "C++ 17", "Python3", "Java"];
const user = () => ({
  uid: "ui-test",
  username: "UI验收账号",
  roleList: [role],
  gender: "secrecy",
  cfUsername: "tourist",
  avatar: "",
  solvedList: [],
  scoreList: [],
  rating: 2100,
  practiceRating: null,
  contestRating: null,
});
const rows = [
  {
    id: 1000,
    pid: 1000,
    problemId: "P1000",
    title: "二分查找：第一个不小于目标的数",
    difficulty: 1,
    total: 30,
    ac: 19,
    isRemote: false,
    tags: [{ id: 1, name: "二分", color: "#217c9e" }],
  },
  {
    id: 1001,
    pid: 1001,
    problemId: "CF-4A",
    title: "Watermelon",
    difficulty: 1,
    total: 55,
    ac: 42,
    tags: [],
  },
];
function fixture(req) {
  const u = new URL(req.url),
    p = u.pathname;
  requests.push({ path: p, method: req.method, data: req.postData });
  if (p === "/api/get-website-config")
    return {
      name: "HOJ",
      shortName: "HOJ",
      openPublicDiscussion: true,
      openGroupDiscussion: true,
      openContestComment: true,
      register: true,
      recordName: "",
      recordUrl: "",
      description: "old branding",
    };
  if (p === "/api/get-user-auth-info") return { roles: [role] };
  if (p === "/api/msg/unread")
    return { comment: 0, reply: 0, like: 0, sys: 0, mine: 0 };
  if (
    p === "/api/home-carousel" ||
    p === "/api/get-recent-contest" ||
    p === "/api/get-recent-other-contest"
  )
    return [];
  if (p === "/api/get-common-announcement")
    return {
      total: 1,
      records: [
        {
          id: 1,
          title: "集训安排与平台使用说明",
          content: "开始今天的训练，完成后记录思路与复盘。",
          username: "教练",
          gmtCreate: "2026-10-13 09:00:00",
        },
      ],
    };
  if (p === "/api/get-recent-seven-ac-rank")
    return [
      { uid: "1", username: "chen", ac: 17 },
      { uid: "2", username: "lin", ac: 14 },
      { uid: "3", username: "zhou", ac: 12 },
    ];
  if (p === "/api/get-last-week-submission-statistics")
    return {
      dateStrList: [
        "10-07",
        "10-08",
        "10-09",
        "10-10",
        "10-11",
        "10-12",
        "10-13",
      ],
      acCountList: [10, 15, 21, 8, 31, 25, 18],
      totalCountList: [18, 22, 29, 15, 45, 33, 24],
    };
  if (p === "/api/get-recent-updated-problem") return rows;
  if (p === "/api/get-problem-list")
    return { records: rows, total: rows.length };
  if (
    p === "/api/get-all-problem-tags" ||
    p === "/api/get-problem-tags-and-classification"
  )
    return [];
  if (p === "/api/get-user-problem-status")
    return { 1000: { status: -10 }, 1001: { status: 0 } };
  if (
    p === "/api/get-problem-detail" ||
    p === "/api/get-contest-problem-details"
  ) {
    const id =
      u.searchParams.get("problemId") ||
      u.searchParams.get("displayId") ||
      "P1000";
    return {
      problem: {
        ...rows[id === "P1000" ? 0 : 1],
        problemId: id,
        type: 0,
        timeLimit: 1000,
        memoryLimit: 256,
        stackLimit: 128,
        description:
          "<p>给定一个按升序排列的整数数组和目标值，找出第一个不小于目标值的位置。</p>",
        input: "第一行输入 n 和 x，第二行输入数组元素。",
        output: "输出下标；如果不存在则输出 -1。",
        hint: "注意重复元素和边界条件。",
        examples: "<input>5 6\n1 3 6 6 9</input><output>2</output>",
        source: "算法基础训练",
        author: "教练",
        isRemote: false,
        ...(objectiveType
          ? {
              questionType: objectiveType,
              questionVersion: 1,
              title: "算法基础：二分查找",
              description:
                "<p>有序数组包含 1024 个不同的元素。分析二分查找的适用条件和时间复杂度。</p>",
              options: [
                { id: "A", text: "输入序列需要按比较关系有序。" },
                { id: "B", text: "每次比较最多排除约一半的候选区间。" },
                { id: "C", text: "最坏情况下需要遍历全部元素。" },
              ],
              blanks: [
                { id: "complexity", label: "最坏时间复杂度" },
                { id: "comparisons", label: "最多比较次数" },
              ],
            }
          : {}),
      },
      languages,
      codeTemplate: {
        "C++ 17":
          "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    return 0;\n}",
        Python3: "import sys\n",
        Java: "public class Main {\n}\n",
        C: "#include <stdio.h>\n",
      },
      tags: rows[0].tags,
      problemCount: { total: 30, ac: 19, wa: 10, ce: 1 },
    };
  }
  if (p === "/api/languages")
    return languages.map((name) => ({
      name,
      contentType: name === "Python3" ? "text/x-python" : "text/x-c++src",
      description: name,
      template: "",
      compileCommand: "",
    }));
  if (p === "/api/get-full-screen-problem-list")
    return [
      { pid: 1000, problemId: "P1000", title: rows[0].title },
      { pid: 1001, problemId: "P1001", title: "第二题" },
    ];
  if (p === "/api/get-user-home-info") return user();
  if (p === "/api/get-user-calendar-heatmap")
    return { dataList: [], endDate: "2026-10-13" };
  if (p === "/api/change-userInfo")
    return { ...user(), ...JSON.parse(req.postData) };
  if (p === "/api/submit-problem-test-judge") return "ui-fixture-test";
  if (p === "/api/get-test-judge-result")
    return {
      status: 0,
      time: 1,
      memory: 1024,
      userInput: "1",
      userOutput: "1",
      expectedOutput: "1",
      problemJudgeMode: "default",
    };
  if (p === "/api/submit-problem-judge") return { submitId: 123, status: 5 };
  if (p === "/api/get-submission-detail") return { submission: { status: 0 } };
  if (p.includes("get-contest-info"))
    return {
      id: 1,
      title: "测试比赛",
      type: 0,
      auth: 0,
      status: 0,
      startTime: "2026-10-13 00:00:00",
      endTime: "2026-10-13 23:59:00",
      duration: 86400,
      sealRank: false,
      author: "教练",
    };
  if (p.includes("rank")) return { records: [], total: 0 };
  if (p.includes("list")) return { records: [], total: 0 };
  return [];
}
(async () => {
  let port;
  for (let i = 0; i < 100; i++) {
    const p = path.join(profile, "DevToolsActivePort");
    if (fs.existsSync(p)) {
      port = fs.readFileSync(p, "utf8").split("\n")[0];
      break;
    }
    if (browser.exitCode !== null) throw Error(stderr.slice(-900));
    await sleep(150);
  }
  if (!port) throw Error("Browser timeout");
  const pages = await (
    await fetch("http://127.0.0.1:" + port + "/json/list")
  ).json();
  const ws = new WebSocket(
    pages.find((p) => p.type === "page").webSocketDebuggerUrl
  );
  await new Promise((r, j) => {
    ws.onopen = r;
    ws.onerror = j;
  });
  let id = 0;
  const pending = new Map(),
    errors = [],
    warnings = [];
  const cdp = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const n = ++id;
      pending.set(n, { resolve, reject });
      ws.send(JSON.stringify({ id: n, method, params }));
    });
  ws.onmessage = async (e) => {
    const m = JSON.parse(e.data);
    if (m.id) {
      const p = pending.get(m.id);
      if (p) {
        pending.delete(m.id);
        m.error ? p.reject(m.error) : p.resolve(m.result);
      }
    } else if (m.method === "Runtime.exceptionThrown")
      errors.push(m.params.exceptionDetails);
    else if (
      m.method === "Runtime.consoleAPICalled" &&
      ["error", "warning"].includes(m.params.type)
    )
      warnings.push(
        m.params.args.map((a) => a.value || a.description).join(" ")
      );
    else if (m.method === "Fetch.requestPaused") {
      const r = m.params;
      try {
        const data = fixture(r.request);
        if (
          delayedSubmission &&
          r.request.url.includes("/api/submit-problem-judge")
        )
          await sleep(1600);
        await cdp("Fetch.fulfillRequest", {
          requestId: r.requestId,
          responseCode: 200,
          responseHeaders: [
            { name: "Content-Type", value: "application/json" },
          ],
          body: Buffer.from(
            JSON.stringify({ status: 200, data: data })
          ).toString("base64"),
        });
      } catch (e) {
        errors.push(String(e));
      }
    }
  };
  const ev = async (expression) => {
    const r = await cdp("Runtime.evaluate", {
      expression,
      returnByValue: true,
      awaitPromise: true,
    });
    if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
    return r.result.value;
  };
  const shot = async (name) => {
    await sleep(100);
    const r = await cdp("Page.captureScreenshot", {
      format: "png",
      captureBeyondViewport: false,
    });
    fs.writeFileSync(
      path.join(root, "source-" + name + ".png"),
      Buffer.from(r.data, "base64")
    );
  };
  const route = async (url) => {
    await ev(
      `document.getElementById('app').__vue__.$router.push(${JSON.stringify(
        url
      )}).then(()=>true)`
    );
    await sleep(850);
  };
  const component = (name) =>
    `(function find(vm){if(vm.$options.name===${JSON.stringify(
      name
    )}||vm.$options.__file?.endsWith('/'+${JSON.stringify(
      name
    )}+'.vue'))return vm;for(const c of vm.$children){const found=find(c);if(found)return found}})(document.getElementById('app').__vue__)`;
  const report = { checks: {}, errors, warnings };
  await cdp("Runtime.enable");
  await cdp("Page.enable");
  await cdp("Fetch.enable", { patterns: [{ urlPattern: "*/api/*" }] });
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 1440,
    height: 1080,
    deviceScaleFactor: 1,
    mobile: false,
  });
  await cdp("Page.addScriptToEvaluateOnNewDocument", {
    source: `localStorage.setItem('Web_Language','"zh-CN"');`,
  });
  await cdp("Page.navigate", {
    url: (process.env.UI_REVIEW_URL || "http://127.0.0.1:8066") + "/home",
  });
  for (let attempt = 0; attempt < 50; attempt++) {
    await sleep(500);
    if (await ev(`!!document.querySelector('#header')`)) break;
  }
  await sleep(1200);
  fs.writeFileSync(
    path.join(root, "source-startup.json"),
    JSON.stringify(report, null, 2)
  );
  report.checks.home = await ev(
    `({title:document.title,logo:document.querySelector('.brand-lockup img')?.naturalWidth,nav:[...document.querySelectorAll('#header .el-menu-item,#header .el-submenu__title')].map(x=>x.textContent.trim()),overflow:document.documentElement.scrollWidth>innerWidth})`
  );
  await shot("home");
  await ev("scrollTo(0,420)");
  await sleep(400);
  report.checks.scrolled = await ev(
    `({width:document.querySelector('#header').offsetWidth,height:document.querySelector('#header').offsetHeight,bg:getComputedStyle(document.querySelector('#header')).backgroundColor})`
  );
  await shot("nav-scrolled");
  await cdp("Input.dispatchMouseEvent", { type: "mouseMoved", x: 700, y: 25 });
  await sleep(400);
  report.checks.hovered = await ev(
    `({width:document.querySelector('#header').offsetWidth,height:document.querySelector('#header').offsetHeight})`
  );
  await route("/problem");
  report.checks.sourceList = await ev(
    `document.querySelector('.problem-list')?.innerText || document.body.innerText`
  );
  await shot("problem-list");
  await route("/problem/P1000");
  report.checks.problem = await ev(
    `({cards:[...document.querySelectorAll('.workspace-card-heading strong')].map(x=>x.textContent),languages:${component(
      "ProblemDetails"
    )}.problemData.languages,overflow:document.documentElement.scrollWidth>innerWidth})`
  );
  await shot("problem");
  await ev(`${component("ProblemDetails")}.onChangeLang('C++ 17');`);
  await sleep(100);
  await ev(
    `${component(
      "ProblemDetails"
    )}.$refs.codeEditor.editor.setValue('// cpp draft');`
  );
  await sleep(450);
  await ev(`${component("ProblemDetails")}.onChangeLang('Python3');`);
  await sleep(200);
  await ev(
    `${component(
      "ProblemDetails"
    )}.$refs.codeEditor.editor.setValue('# python draft');`
  );
  await sleep(400);
  await ev(`${component("ProblemDetails")}.onChangeLang('C++ 17');`);
  report.checks.cppRestored = await ev(`${component("ProblemDetails")}.code`);
  await ev(`${component("WorkspaceLayout")}.preset(true)`);
  await sleep(250);
  await shot("learning");
  await ev(`${component("WorkspaceLayout")}.swap()`);
  report.checks.swapped = await ev(`${component("WorkspaceLayout")}.order`);
  fs.writeFileSync(
    path.join(root, "source-review-partial.json"),
    JSON.stringify(report, null, 2)
  );
  const sep = await ev(
    `(()=>{const r=document.querySelector('.workspace-split').getBoundingClientRect();return {x:r.x+8,y:r.y+150}})()`
  );
  await cdp("Input.dispatchMouseEvent", {
    type: "mousePressed",
    ...sep,
    button: "left",
    clickCount: 1,
  });
  await cdp("Input.dispatchMouseEvent", {
    type: "mouseMoved",
    x: sep.x + 80,
    y: sep.y,
    button: "left",
    buttons: 1,
  });
  await cdp("Input.dispatchMouseEvent", {
    type: "mouseReleased",
    x: sep.x + 80,
    y: sep.y,
    button: "left",
    clickCount: 1,
  });
  await sleep(250);
  report.checks.resized = await ev(`${component("WorkspaceLayout")}.weights`);
  await cdp("Page.reload");
  await sleep(1900);
  report.checks.restored = await ev(
    `({code:${component("ProblemDetails")}.code,order:${component(
      "WorkspaceLayout"
    )}.order,weights:${component("WorkspaceLayout")}.weights})`
  );
  await ev(`${component("ProblemDetails")}.openTestCaseDrawer=true`);
  await sleep(200);
  report.checks.selfTestInsideEditor = await ev(
    `!!document.querySelector('.workspace-card-body[data-panel=editor] .inline-self-test')`
  );
  await ev(`${component("AppearanceSettings")}.set('theme','dark')`);
  await sleep(250);
  await shot("problem-dark");
  await ev(`${component("AiAssistant")}.show()`);
  await sleep(200);
  await shot("ai");
  await ev(`${component("AiAssistant")}.close()`);
  report.checks.roles = {};
  for (const r of ["user", "admin", "root", "problem_admin"]) {
    role = r;
    await ev(
      `document.getElementById('app').__vue__.$store.commit('changeUserToken','ui-fixture-token');document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:${JSON.stringify(
        user()
      )}})`
    );
    await sleep(250);
    report.checks.roles[r] = await ev(
      `document.getElementById('app').__vue__.$store.getters.isAdminRole`
    );
    await ev(
      `document.querySelector('#header .drop-menu .el-dropdown-link').click()`
    );
    await sleep(250);
    report.checks.roles[r + "Menu"] = await ev(
      `[...document.querySelectorAll('.el-dropdown-menu__item')].some(x=>x.textContent.includes('管理后台') && x.getBoundingClientRect().height>0)`
    );
    await ev(`document.body.click()`);
  }
  role = "user";
  await ev(
    `document.getElementById('app').__vue__.$store.commit('changeUserInfo',{userInfo:${JSON.stringify(
      user()
    )}})`
  );
  await route("/setting");
  await ev(`${component("Setting")}.activeName='Platforms'`);
  await sleep(200);
  await shot("accounts");
  await route("/training/1/problem/P1000/full-screen?from=review");
  report.checks.neighbors = await ev(
    `({text:document.querySelector('.problem-neighbors')?.innerText,disabled:document.querySelector('.problem-neighbors button')?.disabled})`
  );
  await ev(`${component("ProblemHorizontalMenu")}.adjacent(1)`);
  await sleep(900);
  report.checks.nextPath = await ev("location.pathname+location.search");
  await route("/problem/P1000");
  await ev(
    `${component("ProblemDetails")}.code='int main(){return 0;}';${component(
      "ProblemDetails"
    )}.openTestCaseDrawer=true;`
  );
  await sleep(150);
  await ev(
    `${component("ProblemDetails")}.$refs.codeEditor.userInput='1';${component(
      "ProblemDetails"
    )}.$refs.codeEditor.submitTestJudge()`
  );
  await sleep(1400);
  report.checks.testRequest = requests.filter(
    (r) => r.path === "/api/submit-problem-test-judge"
  );

  await route("/user-home");
  report.checks.ratings = await ev(
    `document.querySelector('.rating-summary').innerText`
  );
  await shot("ratings");
  await route("/problem/P1000");
  await ev(`${component("WorkspaceLayout")}.preset(false)`);
  await sleep(150);
  // Exercise browser drag events with a real DataTransfer payload and the component's DOM handlers.
  await ev(
    `(()=>{const headers=document.querySelectorAll('.workspace-card-heading');const transfer=new DataTransfer();headers[0].dispatchEvent(new DragEvent('dragstart',{bubbles:true,dataTransfer:transfer}));headers[1].closest('.workspace-card').dispatchEvent(new DragEvent('drop',{bubbles:true,dataTransfer:transfer}));headers[0].dispatchEvent(new DragEvent('dragend',{bubbles:true,dataTransfer:transfer}));})()`
  );
  report.checks.dragOrder = await ev(`${component("WorkspaceLayout")}.order`);
  await ev(
    `document.querySelector('.workspace-card-heading button:last-child').click()`
  );
  await sleep(100);
  report.checks.expanded = await ev(
    `document.querySelectorAll('.workspace-card.expanded').length`
  );
  await cdp("Input.dispatchKeyEvent", {
    type: "keyDown",
    key: "Escape",
    code: "Escape",
    windowsVirtualKeyCode: 27,
  });
  await cdp("Input.dispatchKeyEvent", {
    type: "keyUp",
    key: "Escape",
    code: "Escape",
    windowsVirtualKeyCode: 27,
  });
  report.checks.escaped = await ev(
    `document.querySelectorAll('.workspace-card.expanded').length`
  );
  await ev(
    `${component("WorkspaceLayout")}.preset(false);${component(
      "ProblemDetails"
    )}.onChangeLang('C++ 17');${component("ProblemDetails")}.code='';`
  );
  await sleep(500);
  await cdp("Page.reload");
  await sleep(1900);
  report.checks.emptyDraft = await ev(`${component("ProblemDetails")}.code`);
  // A response from the preceding problem must not modify the current problem's submission state.
  await ev(`${component("ProblemDetails")}.code='int main(){return 0;}';`);
  delayedSubmission = true;
  await ev(`${component("ProblemDetails")}.submitCode()`);
  await sleep(150);
  await route("/problem/P1001");
  await sleep(1800);
  delayedSubmission = false;
  report.checks.staleSubmission = await ev(
    `({problem:${component("ProblemDetails")}.problemID,status:${component(
      "ProblemDetails"
    )}.statusVisible,submitted:${component("ProblemDetails")}.submitted})`
  );
  await route("/contest/1/problem/P1000/full-screen");
  await ev(`${component("AiAssistant")}.show()`);
  report.checks.contestAi = await ev(
    `({disabled:document.querySelector('.ai-popup textarea').disabled,text:document.querySelector('.ai-popup').innerText})`
  );
  await ev(`${component("AiAssistant")}.close()`);
  report.checks.objective = {};
  for (const type of ["SINGLE_CHOICE", "MULTIPLE_CHOICE", "FILL_BLANK"]) {
    objectiveType = type;
    await route("/problem/" + type);
    await ev(`${component("WorkspaceLayout")}.preset(false)`);
    await sleep(100);
    report.checks.objective[type] = await ev(
      `({title:document.querySelector('.objective-answer h3')?.innerText,codeEditors:document.querySelectorAll('.CodeMirror').length,disabled:document.querySelector('.answer-footer button')?.disabled})`
    );
    await shot("objective-" + type.toLowerCase());
  }
  objectiveType = "";

  report.checks.mobile = {};
  await ev(`${component("AppearanceSettings")}.set('theme','light')`);
  await cdp("Emulation.setDeviceMetricsOverride", {
    width: 390,
    height: 844,
    deviceScaleFactor: 1,
    mobile: false,
  });
  for (const p of [
    "/home",
    "/problem",
    "/problem/P1000",
    "/setting",
    "/developer",
  ]) {
    await route(p);
    report.checks.mobile[p] = await ev(
      `({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth})`
    );
    await shot("mobile-" + p.replaceAll("/", "-"));
  }

  fs.writeFileSync(
    path.join(root, "source-review-unvalidated.json"),
    JSON.stringify(report, null, 2)
  );
  assert.equal(errors.length, 0, "uncaught browser exceptions");
  assert.equal(report.checks.home.logo, 608);
  for (const label of [
    "首页",
    "训练",
    "比赛",
    "评测",
    "排名",
    "讨论",
    "团队",
    "关于",
  ])
    assert(
      report.checks.home.nav.some((text) => text.includes(label)),
      "missing navigation " + label
    );
  assert(
    report.checks.scrolled.width < report.checks.hovered.width &&
      report.checks.scrolled.height < report.checks.hovered.height
  );
  assert(report.checks.sourceList.includes("Codeforces"));
  assert.equal(report.checks.cppRestored, "// cpp draft");
  assert.equal(report.checks.restored.code, "// cpp draft");
  assert.deepEqual(report.checks.swapped, report.checks.restored.order);
  assert.deepEqual(report.checks.resized, report.checks.restored.weights);
  assert.equal(report.checks.selfTestInsideEditor, true);
  assert.equal(report.checks.roles.user, false);
  assert.equal(report.checks.roles.userMenu, false);
  for (const role of ["admin", "root", "problem_admin"]) {
    assert.equal(report.checks.roles[role], true);
    assert.equal(report.checks.roles[role + "Menu"], true);
  }
  assert.equal(report.checks.neighbors.disabled, true);
  assert.equal(
    report.checks.nextPath,
    "/training/1/problem/P1001/full-screen?from=review"
  );
  assert.equal(report.checks.testRequest.length, 1);
  assert.equal(JSON.parse(report.checks.testRequest[0].data).pid, 1000);
  assert.equal(report.checks.expanded, 1);
  assert.equal(report.checks.escaped, 0);
  assert.equal(report.checks.emptyDraft, "");
  assert.deepEqual(report.checks.dragOrder, [
    "editor",
    "statement",
    "learning",
  ]);
  assert.equal(report.checks.staleSubmission.problem, "P1001");
  assert.equal(report.checks.staleSubmission.status, false);
  assert.equal(report.checks.staleSubmission.submitted, false);
  assert.equal(report.checks.contestAi.disabled, true);
  assert(
    report.checks.ratings.includes("练习 Rating") &&
      report.checks.ratings.includes("比赛 Rating")
  );
  for (const result of Object.values(report.checks.mobile))
    assert.equal(result.width, result.scroll);
  for (const result of Object.values(report.checks.objective)) {
    assert.equal(result.title, "写下你的答案");
    assert.equal(result.codeEditors, 0);
    assert.equal(result.disabled, true);
  }
  report.validated = true;

  report.requestPaths = [...new Set(requests.map((x) => x.path))];
  fs.writeFileSync(
    path.join(root, "source-review.json"),
    JSON.stringify(report, null, 2)
  );
  console.log(JSON.stringify(report, null, 2));
  await cdp("Browser.close");
  ws.close();
})()
  .catch((e) => {
    console.error(e);
    process.exitCode = 1;
  })
  .finally(() => {
    clearTimeout(watchdog);
    browser.kill();
  });
