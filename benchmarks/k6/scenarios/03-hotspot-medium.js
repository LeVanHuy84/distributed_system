import { placeOrder } from '../lib/client.js';
import { pickUniformSku } from '../lib/config.js';
import { createSummaryHandler } from '../lib/metrics.js';

export const options = {
    vus: 500,
    duration: '30s',
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        // Không ép system_error_rate fail quá gắt để quan sát tín hiệu HikariCP timeout (5000ms)
        orders_created_201: ['count>0'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    const skuId = pickUniformSku('SKU-HOT100-', 100, 3);
    placeOrder(skuId);
}

export const handleSummary = createSummaryHandler('03-hotspot-medium');
