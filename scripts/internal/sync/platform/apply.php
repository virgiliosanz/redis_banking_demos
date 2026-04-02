<?php

$source_json = getenv('SYNC_SOURCE_SNAPSHOT_JSON');
if ($source_json === false || $source_json === '') {
    fwrite(STDERR, "Missing SYNC_SOURCE_SNAPSHOT_JSON\n");
    exit(1);
}

$source = json_decode($source_json, true);
if (!is_array($source) || !isset($source['allowlist_options']) || !is_array($source['allowlist_options'])) {
    fwrite(STDERR, "Invalid SYNC_SOURCE_SNAPSHOT_JSON\n");
    exit(1);
}

$result = [
    'site' => home_url(),
    'updated_options' => [],
    'code_drift' => [],
];

$stylesheet = (string) get_option('stylesheet', '');
$template = (string) get_option('template', '');
$active_plugins = get_option('active_plugins', []);
if (!is_array($active_plugins)) {
    $active_plugins = [];
}
sort($active_plugins);

$source_active_plugins = $source['active_plugins'] ?? [];
if (!is_array($source_active_plugins)) {
    $source_active_plugins = [];
}
sort($source_active_plugins);

if (($source['stylesheet'] ?? '') !== $stylesheet) {
    $result['code_drift']['stylesheet'] = ['from' => $stylesheet, 'to' => $source['stylesheet'] ?? ''];
}
if (($source['template'] ?? '') !== $template) {
    $result['code_drift']['template'] = ['from' => $template, 'to' => $source['template'] ?? ''];
}
if ($source_active_plugins !== $active_plugins) {
    $result['code_drift']['active_plugins'] = ['from' => $active_plugins, 'to' => $source_active_plugins];
}

foreach ($source['allowlist_options'] as $option_name => $source_value) {
    $current_value = get_option($option_name, null);
    if ($option_name === 'nav_menu_locations' && $current_value === null) {
        $current_value = [];
    }
    if (!is_array($current_value) && is_array($source_value)) {
        $current_value = [];
    }
    if (is_array($current_value)) {
        ksort($current_value);
    }
    if (is_array($source_value)) {
        ksort($source_value);
    }

    if ($current_value === $source_value) {
        continue;
    }

    update_option($option_name, $source_value);
    $result['updated_options'][] = $option_name;
}

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
