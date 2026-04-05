<?php
/**
 * WordPress metrics collector.
 *
 * Executed via: wp eval-file /opt/project/scripts/internal/wp-metrics.php --path=/srv/wp/site
 * Requires N9_SITE_CONTEXT env var (live|archive).
 * Outputs JSON: {"metrics": {...}}
 */

$context = getenv('N9_SITE_CONTEXT');
if ($context === false || $context === '') {
    $context = 'live';
}
$is_live = ($context === 'live');

global $wpdb;
$metrics = [];

// --- WP-Cron (live only) ---
if ($is_live) {
    $cron_array = _get_cron_array();
    $now = time();
    $total = 0;
    $overdue = 0;
    $max_overdue = 0;

    if (is_array($cron_array)) {
        foreach ($cron_array as $timestamp => $hooks) {
            if (!is_array($hooks)) {
                continue;
            }
            foreach ($hooks as $hook => $events) {
                if (!is_array($events)) {
                    continue;
                }
                $count = count($events);
                $total += $count;
                if ($timestamp < $now) {
                    $overdue += $count;
                    $age = $now - (int) $timestamp;
                    if ($age > $max_overdue) {
                        $max_overdue = $age;
                    }
                }
            }
        }
    }

    $metrics['cron_events_total'] = $total;
    $metrics['cron_events_overdue'] = $overdue;
    $metrics['cron_events_overdue_max_age'] = $max_overdue;
}

// --- Database ---
$db_size_row = $wpdb->get_row(
    $wpdb->prepare(
        "SELECT SUM(data_length + index_length) AS total_size FROM information_schema.TABLES WHERE table_schema = %s",
        DB_NAME
    )
);
$metrics['db_size_mb'] = $db_size_row && $db_size_row->total_size
    ? round((float) $db_size_row->total_size / (1024 * 1024), 2)
    : 0;

$autoload_row = $wpdb->get_row(
    "SELECT SUM(LENGTH(option_value)) AS total_size, COUNT(*) AS total_count FROM {$wpdb->options} WHERE autoload = 'yes'"
);
$metrics['autoload_size_kb'] = $autoload_row && $autoload_row->total_size
    ? round((float) $autoload_row->total_size / 1024, 2)
    : 0;
$metrics['autoload_count'] = $autoload_row ? (int) $autoload_row->total_count : 0;

$transients_count = (int) $wpdb->get_var(
    "SELECT COUNT(*) FROM {$wpdb->options} WHERE option_name LIKE '_transient_%'"
);
$metrics['transients_count'] = $transients_count;

// --- Content (live only) ---
if ($is_live) {
    $post_counts = wp_count_posts('post');
    $metrics['posts_published'] = isset($post_counts->publish) ? (int) $post_counts->publish : 0;
    $metrics['posts_draft'] = isset($post_counts->draft) ? (int) $post_counts->draft : 0;

    $page_counts = wp_count_posts('page');
    $metrics['pages_published'] = isset($page_counts->publish) ? (int) $page_counts->publish : 0;
}

// --- Updates (live only) ---
if ($is_live) {
    if (!function_exists('get_plugin_updates')) {
        require_once ABSPATH . 'wp-admin/includes/update.php';
        require_once ABSPATH . 'wp-admin/includes/plugin.php';
    }
    wp_update_plugins();
    $plugin_updates = get_plugin_updates();
    $metrics['plugins_update_available'] = is_array($plugin_updates) ? count($plugin_updates) : 0;

    wp_update_themes();
    $theme_updates = get_theme_updates();
    $metrics['themes_update_available'] = is_array($theme_updates) ? count($theme_updates) : 0;
}

// --- PHP errors (both) ---
$error_count = 0;
$debug_log_path = '';
if (defined('WP_DEBUG_LOG') && is_string(WP_DEBUG_LOG) && WP_DEBUG_LOG !== '') {
    $debug_log_path = WP_DEBUG_LOG;
} elseif (defined('WP_DEBUG_LOG') && WP_DEBUG_LOG === true) {
    $debug_log_path = ABSPATH . 'wp-content/debug.log';
} else {
    $debug_log_path = ABSPATH . 'wp-content/debug.log';
}

if (file_exists($debug_log_path) && is_readable($debug_log_path)) {
    $tail_cmd = sprintf('tail -n 500 %s 2>/dev/null', escapeshellarg($debug_log_path));
    $tail_output = shell_exec($tail_cmd);
    if (is_string($tail_output)) {
        $lines = explode("\n", $tail_output);
        foreach ($lines as $line) {
            if (
                strpos($line, 'PHP Fatal') !== false
                || strpos($line, 'PHP Warning') !== false
                || strpos($line, 'PHP Notice') !== false
            ) {
                $error_count++;
            }
        }
    }
}
$metrics['php_error_count'] = $error_count;

echo wp_json_encode(['metrics' => $metrics], JSON_UNESCAPED_SLASHES);
