import http from 'k6/http';

import {
    BASE_URL,
    STRATEGY,
    USER_ID,
} from './config.js';

import {
    recordOrderMetrics,
} from './metrics.js';

// =====================================================
// HTTP CONFIGURATION
// =====================================================

// HTTP 201 và 409 đều là response hợp lệ trong benchmark.
http.setResponseCallback(
    http.expectedStatuses(201, 409)
);

// =====================================================
// PLACE ORDER
// =====================================================

/**
 * Gửi request tạo đơn hàng.
 *
 * @param {string} skuId - SKU cần đặt hàng.
 * @returns {object} HTTP response.
 */
export function placeOrder(skuId) {
    const payload = JSON.stringify({
        userId: `${USER_ID}-${__VU}`,
        skuId: skuId,
        quantity: 1,
        unitPrice: 100.00,
    });

    const params = {
        headers: {
            'Content-Type': 'application/json',
            'X-Concurrency-Strategy': STRATEGY,
        },
        tags: {
            name: 'POST /api/v1/orders',
            strategy: STRATEGY,
        },
    };

    const res = http.post(
        `${BASE_URL}/api/v1/orders`,
        payload,
        params
    );

    // Ghi nhận metrics dựa trên HTTP status.
    // Không đọc res.body vì có thể bật discardResponseBodies.
    recordOrderMetrics(res);

    return res;
}
