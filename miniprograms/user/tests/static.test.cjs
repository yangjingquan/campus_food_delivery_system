const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

test('AppID and all declared native pages are present', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
  assert.equal(project.appid, 'wxb2ef21a4d9800f4e');
  const pages = [...app.pages, ...app.subPackages.flatMap(pack => pack.pages.map(page => pack.root + '/' + page))];
  for (const page of pages) {
    for (const extension of ['js', 'json', 'wxml']) assert.ok(fs.existsSync(path.join(root, `${page}.${extension}`)), `${page}.${extension} is missing`);
  }
});

test('WXML tap handlers resolve to page methods', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  for (const page of app.pages) {
    const stem = path.join(root, page);
    const markup = fs.readFileSync(`${stem}.wxml`, 'utf8');
    const source = fs.readFileSync(`${stem}.js`, 'utf8');
    const handlers = [...markup.matchAll(/bindtap="([A-Za-z_$][\w$]*)"/g)].map(match => match[1]);
    for (const handler of handlers) assert.match(source, new RegExp(`(?:async\\s+)?${handler}\\s*\\(`), `${page} is missing ${handler}()`);
  }
});

test('WXML expressions use data expressions rather than JavaScript method calls', () => {
  const files = [];
  function walk(dir) { for (const entry of fs.readdirSync(dir, { withFileTypes: true })) { const full = path.join(dir, entry.name); if (entry.isDirectory()) walk(full); else if (full.endsWith('.wxml')) files.push(full); } }
  walk(root);
  const sources = new Map(files.map(file => [file, fs.readFileSync(file, 'utf8')]));
  const statesFiles = new Set();
  for (const [file, text] of sources) {
    if (!/<wxs module="states" src="[^"]+utils\/states\.wxs"\s*\/>/.test(text)) continue;
    statesFiles.add(file);
    for (const match of text.matchAll(/<include src="([^"]+)"/g)) {
      statesFiles.add(path.resolve(path.dirname(file), match[1]));
    }
  }
  for (const [file, text] of sources) {
    const expressions = [...text.matchAll(/{{([\s\S]*?)}}/g)].map(match => match[1]);
    for (const expression of expressions) {
      // WXS runs in the template runtime. Ordinary JavaScript methods are still unsupported.
      const code = expression.replace(/'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"/g, '');
      const markup = statesFiles.has(file) ? code.replace(/\bstates\.\w+\s*\(/g, '(') : code;
      assert.doesNotMatch(markup, /\w+\s*\(|\.(?:toFixed|reduce|map|slice)\b/, `${path.relative(root, file)}: ${expression}`);
    }
  }
});
