import { placeOrder } from '../lib/client.js';
import { pickUniformSku } from '../lib/config.js';
import { createSummaryHandler } from '../lib/metrics.js';

export const options = {
    vus: 100,
    duration: '30s',
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        orders_created_201: ['count>0'],
        system_error_rate: ['rate<0.01'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    // Khớp seed-benchmark.sql: SKU-HOT100-001 .. SKU-HOT100-100
    const skuId = pickUniformSku('SKU-HOT100-', 100, 3);
    placeOrder(skuId);
}

export const handleSummary = createSummaryHandler('02-hotspot-low');
