/*
 * Copyright 2026 Vasantha Kumar Kesavan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseVariables, smsSummary } from '../src/draftLogic.ts';

test('variables can be typed with commas, spaces or braces and repeats are dropped', () => {
  assert.deepEqual(parseVariables('firstName, orderId'), ['firstName', 'orderId']);
  assert.deepEqual(parseVariables('{{firstName}} {{ orderId }};firstName'), ['firstName', 'orderId']);
  assert.deepEqual(parseVariables('  ,  '), []);
});

test('the SMS estimate reads as segments, encoding and length', () => {
  assert.equal(smsSummary({ encoding: 'GSM-7', characters: 87, segments: 1 }), '1 segment, GSM-7, about 87 characters');
  assert.equal(smsSummary({ encoding: 'UCS-2', characters: 120, segments: 2 }), '2 segments, UCS-2, about 120 characters');
});
