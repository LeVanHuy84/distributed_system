import { placeOrder } from '../lib/client.js';
import { pickUniformSku } from '../lib/config.js';
import { createSummaryHandler } from '../lib/metrics.js';

export const options = {
    vus: 1000,
    duration: '30s',
    discardResponseBodies: true,
    // Giữ nguyên HTTP keep-alive mặc định (không đặt noConnectionReuse: true)
    // để tránh cạn ephemeral ports trên Windows khi chạy 1.000 VUs.
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        orders_created_201: ['count>0'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    const skuId = pickUniformSku('SKU-HOT100-', 100, 3);
    placeOrder(skuId);
}

export const handleSummary = createSummaryHandler('04-hotspot-high');
