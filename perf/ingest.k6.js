// Copyright 2026 Vasantha Kumar
// Licensed under the Apache License, Version 2.0 (see LICENSE).
// @author Vasantha Kumar <vasantha.kumar@hotmail.com>
//
// Load model for the Client API. Run through perf/run-benchmark.mjs, which seeds the environment and passes
// BASE_URL and API_KEY. Scenarios run one after another so their numbers do not contaminate each other.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL;
const KEY = __ENV.API_KEY;
const RATE = Number(__ENV.RATE || 200);
const HEADERS = { 'X-API-Key': KEY, 'Content-Type': 'application/json' };
export const accepted = new Counter('accepted_messages');

export const options = {
  scenarios: {
    single_send: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 100, maxVUs: 600, exec: 'singleSend', startTime: '0s',
    },
    status_read: {
      executor: 'constant-arrival-rate', rate: RATE, timeUnit: '1s', duration: '30s',
      preAllocatedVUs: 50, maxVUs: 300, exec: 'statusRead', startTime: '70s',
    },
  },
  thresholds: {
    'http_req_failed{scenario:single_send}': ['rate<0.01'],
    'http_req_duration{scenario:single_send}': ['p(99)<500'],
    'http_req_duration{scenario:status_read}': ['p(99)<500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const ids = [];
  for (let i = 0; i < 50; i++) {
    const r = http.post(`${BASE}/v1/notifications`, JSON.stringify({ channel: 'SMS', recipient: '+14155550' + (100 + i), templateName: 'otp-sms', variables: { code: '1' } }), { headers: HEADERS });
    ids.push(r.json('requestId'));
  }
  return { ids };
}

export function singleSend() {
  const n = exec.scenario.iterationInTest;
  const body = JSON.stringify({
    channel: 'SMS', recipient: '+1415555' + String(1000 + (n % 9000)),
    templateName: 'otp-sms', variables: { code: String(100000 + (n % 900000)) },
  });
  const res = http.post(`${BASE}/v1/notifications`, body, { headers: HEADERS });
  if (check(res, { 'accepted 202': (r) => r.status === 202 })) {
    accepted.add(1);
  } else if (exec.scenario.iterationInTest % 50 === 0) {
    console.warn(`rejected: status ${res.status} ${String(res.body).slice(0, 120)}`);
  }
}

export function statusRead(data) {
  const id = data.ids[exec.scenario.iterationInTest % data.ids.length];
  check(http.get(`${BASE}/v1/notifications/${id}`, { headers: HEADERS }), { 'status 200': (r) => r.status === 200 });
}
