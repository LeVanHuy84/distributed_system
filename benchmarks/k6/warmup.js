import { placeOrder } from './lib/client.js';
import { pickUniformSku } from './lib/config.js';
import { createSummaryHandler } from './lib/metrics.js';

export const options = {
    vus: 20,
    duration: '10s',
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        // Chỉ chặn lỗi 4xx và đảm bảo có ít nhất 1 đơn thành công để phát hiện lỗi cấu hình sớm.
        orders_created_201: ['count>0'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    // Khớp seed-benchmark.sql: SKU-BENCH-WARMUP-01 .. SKU-BENCH-WARMUP-10
    const skuId = pickUniformSku('SKU-BENCH-WARMUP-', 10, 2);
    placeOrder(skuId);
}

export const handleSummary = createSummaryHandler('warmup');
