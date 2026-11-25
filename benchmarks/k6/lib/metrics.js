import { Counter, Rate, Trend } from 'k6/metrics';

import {
    RESULT_DIR,
    RUN_ID,
    STRATEGY,
    THREADS_MODE,
} from './config.js';

// =====================================================
// CUSTOM METRICS
// =====================================================

// Số đơn hàng được tạo thành công (HTTP 201).
export const ordersCreated = new Counter('orders_created_201');

// Số request bị conflict hoặc hết hàng (HTTP 409).
export const ordersConflict = new Counter('orders_conflict_409');

// Số lỗi hệ thống (HTTP 5xx hoặc timeout).
export const systemErrors = new Counter('system_errors');

// Tỷ lệ lỗi hệ thống.
export const systemErrorRate = new Rate('system_error_rate');

// Các lỗi client khác 409 (400, 404, ...).
export const clientErrors = new Counter('client_errors_4xx');

// Latency tách riêng cho nhánh thành công (201) và nhánh conflict/hết hàng (409).
export const orderSuccessDuration = new Trend('order_success_duration', true);
export const orderConflictDuration = new Trend('order_conflict_duration', true);

// =====================================================
// RECORD ORDER METRICS
// =====================================================

/**
 * Ghi nhận metrics dựa trên HTTP status.
 *
 * 201: Đặt hàng thành công.
 * 409: Conflict / hết hàng.
 * 4xx: Request không hợp lệ.
 * 5xx: Lỗi server.
 * 0: Timeout hoặc lỗi kết nối.
 */
export function recordOrderMetrics(res) {
    const status = res.status;
    const duration = res.timings ? res.timings.duration : 0;

    if (status === 201) {
        ordersCreated.add(1);
        orderSuccessDuration.add(duration);
        systemErrorRate.add(false);
        return;
    }

    if (status === 409) {
        ordersConflict.add(1);
        orderConflictDuration.add(duration);
        systemErrorRate.add(false);
        return;
    }

    if (status >= 400 && status < 500) {
        clientErrors.add(1);
        systemErrorRate.add(false);
        return;
    }

    if (status >= 500 || status === 0) {
        systemErrors.add(1);
        systemErrorRate.add(true);
        return;
    }

    // Các status không mong đợi khác.
    systemErrorRate.add(false);
}

// =====================================================
// SUMMARY HANDLER
// =====================================================

function metricVal(data, metricName, stat) {
    const metric = data.metrics && data.metrics[metricName];
    if (!metric || !metric.values || metric.values[stat] === undefined) {
        return 'N/A';
    }
    const v = metric.values[stat];
    return typeof v === 'number' ? v.toFixed(2) : String(v);
}

function metricCount(data, metricName) {
    const metric = data.metrics && data.metrics[metricName];
    if (!metric || !metric.values || metric.values.count === undefined) {
        return 0;
    }
    return metric.values.count;
}

function formatTextSummary(scenarioName, data, filePath) {
    const totalReqs = metricCount(data, 'http_reqs');
    const rps = metricVal(data, 'http_reqs', 'rate');
    const created = metricCount(data, 'orders_created_201');
    const conflict = metricCount(data, 'orders_conflict_409');
    const err4xx = metricCount(data, 'client_errors_4xx');
    const errSys = metricCount(data, 'system_errors');

    return [
        '',
        `================ FlashOrder Benchmark Summary ================`,
        `Scenario      : ${scenarioName}`,
        `Strategy      : ${STRATEGY} | Threads: ${THREADS_MODE}`,
        `Total Reqs    : ${totalReqs} (${rps} req/s)`,
        `Status Counts : 201=${created} | 409=${conflict} | 4xx=${err4xx} | 5xx/Timeout=${errSys}`,
        `All Latency   : avg=${metricVal(data, 'http_req_duration', 'avg')}ms | p50=${metricVal(data, 'http_req_duration', 'med')}ms | p95=${metricVal(data, 'http_req_duration', 'p(95)')}ms | p99=${metricVal(data, 'http_req_duration', 'p(99)')}ms | max=${metricVal(data, 'http_req_duration', 'max')}ms`,
        `201 Latency   : avg=${metricVal(data, 'order_success_duration', 'avg')}ms | p95=${metricVal(data, 'order_success_duration', 'p(95)')}ms | p99=${metricVal(data, 'order_success_duration', 'p(99)')}ms`,
        `409 Latency   : avg=${metricVal(data, 'order_conflict_duration', 'avg')}ms | p95=${metricVal(data, 'order_conflict_duration', 'p(95)')}ms | p99=${metricVal(data, 'order_conflict_duration', 'p(99)')}ms`,
        `JSON Saved    : ${filePath}`,
        `==============================================================`,
        '',
    ].join('\n');
}

/**
 * Tạo summary handler cho từng scenario.
 *
 * File output:
 * {RESULT_DIR}/{scenario}_{strategy}_{threadsMode}_{runId}.json
 */
export function createSummaryHandler(scenarioName) {
    return function handleSummary(data) {
        const fileName = `${scenarioName}_${STRATEGY}_${THREADS_MODE}_${RUN_ID}.json`;
        const filePath = `${RESULT_DIR}/${fileName}`;

        return {
            stdout: formatTextSummary(scenarioName, data, filePath),
            [filePath]: JSON.stringify(data, null, 2),
        };
    };
}
