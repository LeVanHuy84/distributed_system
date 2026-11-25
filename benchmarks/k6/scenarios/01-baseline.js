import { placeOrder } from '../lib/client.js';
import { pickUniformSku } from '../lib/config.js';
import { createSummaryHandler } from '../lib/metrics.js';

export const options = {
    vus: 100,
    duration: '60s',
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        orders_created_201: ['count>0'],
        system_error_rate: ['rate<0.001'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    // Khớp seed-benchmark.sql: SKU-BASE-00001 .. SKU-BASE-10000
    const skuId = pickUniformSku('SKU-BASE-', 10000, 5);
    placeOrder(skuId);
}

export const handleSummary = createSummaryHandler('01-baseline');
