import { placeOrder } from '../lib/client.js';
import { formatSku } from '../lib/config.js';
import { createSummaryHandler } from '../lib/metrics.js';

// Khớp seed-benchmark.sql: 1 SKU duy nhất với total_stock = 500
const EXTREME_SKU_ID = formatSku('SKU-EXTREME-', 1, 3); // 'SKU-EXTREME-001'

export const options = {
    vus: 1000,
    duration: '10s',
    discardResponseBodies: true,
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    thresholds: {
        // Bất biến Zero-Overselling: phải có đơn 201 và tuyệt đối không được vượt quá 500
        orders_created_201: ['count>0', 'count<=500'],
        client_errors_4xx: ['count==0'],
    },
};

export default function () {
    placeOrder(EXTREME_SKU_ID);
}

export const handleSummary = createSummaryHandler('05-extreme-contention');
