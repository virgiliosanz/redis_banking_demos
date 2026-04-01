<?php

declare(strict_types=1);

if (!defined('ABSPATH')) {
    define('ABSPATH', __DIR__ . '/../');
}

if (!defined('WP_ENVIRONMENT_TYPE')) {
    define('WP_ENVIRONMENT_TYPE', getenv('WP_ENVIRONMENT_TYPE') ?: 'staging');
}

define('DISALLOW_FILE_EDIT', true);
define('AUTOMATIC_UPDATER_DISABLED', true);
define('WP_DEBUG', false);
define('WP_DEBUG_LOG', true);
define('WP_DEBUG_DISPLAY', false);
define('FORCE_SSL_ADMIN', false);

if (($elasticsearchUrl = getenv('ELASTICSEARCH_URL')) !== false && $elasticsearchUrl !== '') {
    define('ELASTICSEARCH_URL', $elasticsearchUrl);
}

if (($epHost = getenv('EP_HOST')) !== false && $epHost !== '') {
    define('EP_HOST', $epHost);
} elseif (($elasticsearchUrl = getenv('ELASTICSEARCH_URL')) !== false && $elasticsearchUrl !== '') {
    define('EP_HOST', $elasticsearchUrl);
}

if (($epIndexPrefix = getenv('EP_INDEX_PREFIX')) !== false && $epIndexPrefix !== '') {
    define('EP_INDEX_PREFIX', $epIndexPrefix);
}

if (($epSearchAlias = getenv('EP_SEARCH_ALIAS')) !== false && $epSearchAlias !== '') {
    define('EP_SEARCH_ALIAS', $epSearchAlias);
}

if (!defined('WP_DISABLE_ELASTICSEARCH')) {
    define('WP_DISABLE_ELASTICSEARCH', filter_var(getenv('WP_DISABLE_ELASTICSEARCH') ?: 'false', FILTER_VALIDATE_BOOLEAN));
}

if (($debugLogPath = getenv('WP_DEBUG_LOG_PATH')) !== false && $debugLogPath !== '') {
    define('WP_DEBUG_LOG_PATH', $debugLogPath);
}

if (!defined('WP_CONTENT_DIR')) {
    define('WP_CONTENT_DIR', ABSPATH . 'wp-content');
}

if (!defined('WP_CONTENT_URL') && isset($_SERVER['HTTP_HOST'])) {
    $scheme = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') ? 'https' : 'http';
    define('WP_CONTENT_URL', $scheme . '://' . $_SERVER['HTTP_HOST'] . '/wp-content');
}
