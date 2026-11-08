const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');

test('AppID and all declared native pages are present', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'));
  const project = JSON.parse(fs.readFileSync(path.join(root, 'project.config.json'), 'utf8'));
  assert.equal(project.appid, 'wxb2ef21a4d9800f4e');
  for (const page of app.pages) {
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
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    const expressions = [...text.matchAll(/{{([\s\S]*?)}}/g)].map(match => match[1]);
    for (const expression of expressions) assert.doesNotMatch(expression, /\w+\s*\(|\.(?:toFixed|reduce|map|slice)\b/, `${path.relative(root, file)}: ${expression}`);
  }
});
