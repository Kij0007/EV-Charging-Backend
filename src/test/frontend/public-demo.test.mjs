import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../../main/resources/static/assets/content.js', import.meta.url), 'utf8');
const { content, publicDemoNotice, siteContent } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('normal or missing site information keeps the demo notice absent', () => {
  assert.equal(publicDemoNotice(undefined), '');
  assert.equal(publicDemoNotice({ demo: false, notice: 'Not for normal mode' }), '');
  assert.equal(publicDemoNotice({ demo: 'true', notice: 'Incorrect type' }), '');
  assert.equal(publicDemoNotice({ demo: true, notice: { unexpected: 'configuration' } }), '');
});

test('explicit public-demo mode displays only the published plain-text notice', () => {
  const notice = 'Public demo: use test details only. No online charges. Data may reset.';
  assert.equal(publicDemoNotice({ demo: true, notice, unrelatedConfiguration: 'not displayed' }), notice);
});

test('normal-mode help, privacy and booking content remain exactly the same', () => {
  for (const key of ['help', 'privacy', 'terms']) {
    assert.equal(siteContent(key, { demo: false }), content[key]);
    assert.equal(siteContent(key, undefined), content[key]);
  }
});

test('demo privacy explains separate ephemeral records without mutating normal-mode content', () => {
  const original = JSON.stringify(content);
  const privacy = siteContent('privacy', { demo: true });
  const copy = privacy.sections.map(section => section.body).join(' ');
  assert.match(copy, /separate hosted demonstration database/);
  assert.match(copy, /free database expires/);
  assert.match(copy, /No dedicated support contact or retention guarantee/);
  assert.match(copy, /Use test details only/);
  assert.equal(JSON.stringify(content), original);
});

test('demo help and terms never describe the demonstration as a physical charging arrangement', () => {
  const help = siteContent('help', { demo: true });
  const terms = siteContent('terms', { demo: true });
  assert.match(help.sections.find(section => section.title === 'What a reservation includes').body, /does not start a physical charger/);
  assert.match(terms.sections.find(section => section.title === 'Station operation').body, /No physical station is controlled or activated/);
  assert.match(terms.sections.find(section => section.title === 'Charges and payments').body, /collects no reservation charge/);
});
