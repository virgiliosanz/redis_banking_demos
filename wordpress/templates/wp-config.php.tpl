<?php

declare(strict_types=1);

if (!function_exists('n9_secret')) {
    function n9_secret(string $envName, string $filePath, string $fallback = ''): string
    {
        $envValue = getenv($envName);
        if ($envValue !== false && $envValue !== '') {
            return $envValue;
        }

        if (is_readable($filePath)) {
            $fileValue = trim((string) file_get_contents($filePath));
            if ($fileValue !== '') {
                return $fileValue;
            }
        }

        return $fallback;
    }
}

// --- Context-based DB selection ---
$n9_context = $_SERVER['N9_SITE_CONTEXT'] ?? getenv('N9_SITE_CONTEXT') ?: 'live';

switch ($n9_context) {
    case 'live':
        define('DB_NAME', 'n9_live');
        define('DB_USER', 'wp_live');
        define('DB_PASSWORD', n9_secret('WP_LIVE_DB_PASSWORD', '/run/project-secrets/wp-live-db-password'));
        define('DB_HOST', 'db-live:3306');
        define('EP_INDEX_PREFIX', 'n9-live');
        break;
    case 'archive':
        define('DB_NAME', 'n9_archive');
        define('DB_USER', 'wp_archive');
        define('DB_PASSWORD', n9_secret('WP_ARCHIVE_DB_PASSWORD', '/run/project-secrets/wp-archive-db-password'));
        define('DB_HOST', 'db-archive:3306');
        define('EP_INDEX_PREFIX', 'n9-archive');
        break;
    default:
        die('[wp-config] ERROR: N9_SITE_CONTEXT must be "live" or "archive", got: "' . htmlspecialchars($n9_context, ENT_QUOTES, 'UTF-8') . '"');
}

define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');

// --- Dynamic WP_HOME / WP_SITEURL from HTTP_HOST ---
if (isset($_SERVER['HTTP_HOST'])) {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    define('WP_HOME', $scheme . '://' . $_SERVER['HTTP_HOST']);
    define('WP_SITEURL', $scheme . '://' . $_SERVER['HTTP_HOST']);
} else {
    // CLI fallback
    define('WP_HOME', getenv('WP_HOME') ?: 'http://nuevecuatrouno.test');
    define('WP_SITEURL', getenv('WP_SITEURL') ?: 'http://nuevecuatrouno.test');
}

define('AUTH_KEY', n9_secret('{{AUTH_KEY_ENV}}', '{{AUTH_KEY_FILE}}', 'change-me'));
define('SECURE_AUTH_KEY', n9_secret('{{SECURE_AUTH_KEY_ENV}}', '{{SECURE_AUTH_KEY_FILE}}', 'change-me'));
define('LOGGED_IN_KEY', n9_secret('{{LOGGED_IN_KEY_ENV}}', '{{LOGGED_IN_KEY_FILE}}', 'change-me'));
define('NONCE_KEY', n9_secret('{{NONCE_KEY_ENV}}', '{{NONCE_KEY_FILE}}', 'change-me'));
define('AUTH_SALT', n9_secret('{{AUTH_SALT_ENV}}', '{{AUTH_SALT_FILE}}', 'change-me'));
define('SECURE_AUTH_SALT', n9_secret('{{SECURE_AUTH_SALT_ENV}}', '{{SECURE_AUTH_SALT_FILE}}', 'change-me'));
define('LOGGED_IN_SALT', n9_secret('{{LOGGED_IN_SALT_ENV}}', '{{LOGGED_IN_SALT_FILE}}', 'change-me'));
define('NONCE_SALT', n9_secret('{{NONCE_SALT_ENV}}', '{{NONCE_SALT_FILE}}', 'change-me'));

$table_prefix = '{{TABLE_PREFIX}}';

require '/var/www/shared/config/wp-common.php';

if (!defined('ABSPATH')) {
    define('ABSPATH', __DIR__ . '/');
}

if (file_exists(ABSPATH . 'wp-settings.php')) {
    require_once ABSPATH . 'wp-settings.php';
}
