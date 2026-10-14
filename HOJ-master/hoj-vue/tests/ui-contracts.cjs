const assert = require("assert").strict;
const fs = require("fs");
const path = require("path");

(async () => {
  const source = fs.readFileSync(
    path.join(__dirname, "../src/common/learning.js"),
    "utf8"
  );
  const { finiteRating, cfRating, problemSource, safeSourceUrl, draftKey } =
    await import(
      "data:text/javascript;base64," + Buffer.from(source).toString("base64")
    );
  assert.equal(finiteRating(null), null);
  assert.equal(finiteRating(""), null);
  assert.equal(finiteRating("1500"), null);
  assert.equal(finiteRating(NaN), null);
  assert.equal(finiteRating(0), 0);
  assert.equal(cfRating({ difficulty: 3, ioScore: 100 }), null);
  assert.equal(
    cfRating({ sourcePlatform: "NowCoder", sourceRating: 1800 }),
    null
  );
  assert.equal(cfRating({ sourcePlatform: "CF", sourceRating: 1200 }), 1200);
  assert.equal(
    cfRating({ sourcePlatform: "QOJ", cfRating: 1700, sourceRating: 4 }),
    1700
  );
  assert.equal(
    problemSource({ isRemote: true, problemId: "CF-4A" }),
    "Codeforces"
  );
  assert.equal(problemSource({ pid: 1, problemId: "CF-4A" }), "Codeforces");
  assert.equal(problemSource({ pid: 2, problemId: "QOJ-123" }), "QOJ");
  assert.equal(
    problemSource({ isRemote: false, problemId: "CF-LOCAL" }),
    "本校题库"
  );
  assert.equal(
    problemSource({ isRemote: false, problemId: "1000" }),
    "本校题库"
  );
  assert.equal(safeSourceUrl("javascript:alert(1)"), "");
  assert.equal(safeSourceUrl("data:text/html,<script>alert(1)</script>"), "");
  assert.equal(
    safeSourceUrl("https://codeforces.com/problemset/problem/4/A"),
    "https://codeforces.com/problemset/problem/4/A"
  );
  const base = { problemID: "P1", trainingID: "1", groupID: "2" };
  const key = draftKey("alice", base, "C++ 17");
  assert.notEqual(key, draftKey("bob", base, "C++ 17"));
  assert.notEqual(
    key,
    draftKey("alice", { ...base, contestID: "1" }, "C++ 17")
  );
  assert.notEqual(
    key,
    draftKey("alice", { ...base, trainingID: "2" }, "C++ 17")
  );
  assert.notEqual(key, draftKey("alice", { ...base, groupID: "3" }, "C++ 17"));
  assert.notEqual(key, draftKey("alice", base, "Python3"));
  console.log(
    "UI contracts: 22 assertions passed (missing ratings, source provenance, safe links, draft isolation)."
  );
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
