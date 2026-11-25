const VALID_STRATEGIES = [
    'OPTIMISTIC',
    'PESSIMISTIC',
    'ATOMIC_UPDATE',
];

const VALID_THREADS_MODES = [
    'virtual',
    'platform',
];

export const STRATEGY = __ENV.STRATEGY || 'ATOMIC_UPDATE';

if (!VALID_STRATEGIES.includes(STRATEGY)) {
    throw new Error(
        `Invalid STRATEGY: "${STRATEGY}". Allowed values: ${VALID_STRATEGIES.join(', ')}`
    );
}

export const THREADS_MODE = __ENV.THREADS_MODE || 'virtual';

if (!VALID_THREADS_MODES.includes(THREADS_MODE)) {
    throw new Error(
        `Invalid THREADS_MODE: "${THREADS_MODE}". Allowed values: ${VALID_THREADS_MODES.join(', ')}`
    );
}

// Default nhắm vào k6 chạy trong Docker container (trỏ về host qua host.docker.internal).
// Nếu chạy k6 native trên host, truyền: -e BASE_URL=http://localhost:8080 -e RESULT_DIR=benchmarks/results
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const RESULT_DIR = __ENV.RESULT_DIR || '/benchmarks/results';

// Timestamp dùng cho tên file kết quả.
function createTimestamp() {
    return new Date().toISOString().replace(/[:.]/g, '-');
}

export const RUN_ID = __ENV.RUN_ID || createTimestamp();

// Thông tin người dùng mặc định phục vụ benchmark.
export const USER_ID = __ENV.USER_ID || 'benchmark-user';

// SKU mặc định
export const SKU_PREFIX = __ENV.SKU_PREFIX || 'SKU-';

export const SKU_COUNT = Number(__ENV.SKU_COUNT || 100);

export const SKU_PAD_WIDTH = Number(__ENV.SKU_PAD_WIDTH || 3);

// Validate SKU configuration
if (!Number.isInteger(SKU_COUNT) || SKU_COUNT < 1) {
    throw new Error(
        `Invalid SKU_COUNT: ${SKU_COUNT}. Must be a positive integer.`
    );
}

if (!Number.isInteger(SKU_PAD_WIDTH) || SKU_PAD_WIDTH < 1) {
    throw new Error(
        `Invalid SKU_PAD_WIDTH: ${SKU_PAD_WIDTH}. Must be a positive integer.`
    );
}

/**
 * Format SKU theo prefix và index.
 *
 * Ví dụ:
 * formatSku('SKU-', 1, 3)    -> SKU-001
 * formatSku('FLASH-', 12, 4) -> FLASH-0012
 */
export function formatSku(prefix, index, padWidth = 3) {
    if (!Number.isInteger(index) || index < 1) {
        throw new Error(`Invalid SKU index: ${index}`);
    }

    return `${prefix}${String(index).padStart(padWidth, '0')}`;
}

/**
 * Chọn ngẫu nhiên một SKU với phân phối uniform.
 *
 * index nằm trong khoảng [1, maxCount].
 */
export function pickUniformSku(
    prefix = SKU_PREFIX,
    maxCount = SKU_COUNT,
    padWidth = SKU_PAD_WIDTH
) {
    if (!Number.isInteger(maxCount) || maxCount < 1) {
        throw new Error(`Invalid maxCount: ${maxCount}`);
    }

    const index = Math.floor(Math.random() * maxCount) + 1;

    return formatSku(prefix, index, padWidth);
}
