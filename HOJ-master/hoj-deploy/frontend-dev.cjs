// Container entry point. Linux dependencies stay in a Docker volume; source is read-only.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn, spawnSync } = require('child_process');

const root = process.cwd();
const stamp = path.join(root, 'node_modules', '.hoj-dev-dependencies');
const hash = crypto.createHash('sha256');
for (const file of ['package.json', 'package-lock.json']) hash.update(fs.readFileSync(path.join(root, file)));
hash.update(process.version + process.platform + process.arch);
const fingerprint = hash.digest('hex');
const installed = fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === fingerprint;
if (!installed || !fs.existsSync(path.join(root, 'node_modules/@vue/cli-service/bin/vue-cli-service.js'))) {
  console.log('[HOJ dev] Installing dependencies from package-lock.json...');
  const result = spawnSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: root, stdio: 'inherit' });
  if (result.error) console.error('[HOJ dev]', result.error.message);
  if (result.error || result.status !== 0) process.exit(result.status || 1);
  fs.writeFileSync(stamp, fingerprint);
} else {
  console.log('[HOJ dev] Dependencies unchanged; using the existing Docker volume.');
}

console.log('[HOJ dev] Starting Vue hot reload at http://localhost:8080');
const child = spawn(process.execPath, [
  path.join(root, 'node_modules/@vue/cli-service/bin/vue-cli-service.js'),
  'serve', '--host', '0.0.0.0', '--port', '8066',
], { cwd: root, stdio: 'inherit' });
for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => child.kill(signal));
child.on('error', error => { console.error(error.message); process.exit(1); });
child.on('exit', (code, signal) => process.exit(signal ? 0 : (code || 0)));
