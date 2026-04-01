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

define('DB_NAME', '{{DB_NAME}}');
define('DB_USER', '{{DB_USER}}');
define('DB_PASSWORD', n9_secret('{{DB_PASSWORD_ENV}}', '{{DB_PASSWORD_FILE}}'));
define('DB_HOST', '{{DB_HOST}}');
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');

define('WP_HOME', '{{WP_HOME}}');
define('WP_SITEURL', '{{WP_SITEURL}}');
define('EP_INDEX_PREFIX', '{{EP_INDEX_PREFIX}}');

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
