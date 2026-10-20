import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const source = await readFile(new URL('../../main/resources/static/assets/content.js', import.meta.url), 'utf8');
const { reservationFeedback } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

test('confirmed reservation feedback describes an active reserved space', () => {
  const feedback = reservationFeedback('Confirmed');
  assert.equal(feedback.active, true);
  assert.equal(feedback.status, 'Confirmed');
  assert.equal(feedback.title, 'Your space is reserved.');
});

test('cancelled idempotency replay is presented as cancelled history rather than an active reservation', () => {
  const feedback = reservationFeedback('Cancelled');
  assert.equal(feedback.active, false);
  assert.equal(feedback.status, 'Cancelled');
  assert.equal(feedback.title, 'Your reservation is cancelled.');
  assert.equal(feedback.eyebrow, 'RESERVATION HISTORY');
  assert.match(feedback.description, /booking history/);
  assert.doesNotMatch(feedback.title + feedback.description, /space is reserved/);
});

test('completed idempotency replay is presented as complete history rather than an active reservation', () => {
  const feedback = reservationFeedback('Completed');
  assert.equal(feedback.active, false);
  assert.equal(feedback.status, 'Completed');
  assert.equal(feedback.title, 'Your reservation is complete.');
  assert.equal(feedback.eyebrow, 'RESERVATION HISTORY');
  assert.doesNotMatch(feedback.title + feedback.description, /space is reserved/);
});
