<?php

$source_json = getenv('SYNC_SOURCE_SNAPSHOT_JSON');
if ($source_json === false || $source_json === '') {
    fwrite(STDERR, "Missing SYNC_SOURCE_SNAPSHOT_JSON\n");
    exit(1);
}

$source = json_decode($source_json, true);
if (!is_array($source) || !isset($source['users']) || !is_array($source['users'])) {
    fwrite(STDERR, "Invalid SYNC_SOURCE_SNAPSHOT_JSON\n");
    exit(1);
}

$exclude_csv = getenv('SYNC_EXCLUDE_USER_LOGINS');
$excluded = array_filter(array_map('trim', explode(',', $exclude_csv !== false ? $exclude_csv : 'n9liveadmin,n9archiveadmin')));
$excluded_lookup = array_fill_keys($excluded, true);

$archive_users = get_users([
    'orderby' => 'login',
    'order' => 'ASC',
]);

$archive_by_login = [];
foreach ($archive_users as $archive_user) {
    if (isset($excluded_lookup[$archive_user->user_login])) {
        continue;
    }

    $archive_obj = get_userdata($archive_user->ID);
    if (!$archive_obj instanceof WP_User) {
        continue;
    }

    $roles = array_values($archive_obj->roles);
    sort($roles);

    $caps = get_user_meta($archive_user->ID, $GLOBALS['wpdb']->prefix . 'capabilities', true);
    if (!is_array($caps)) {
        $caps = [];
    }
    ksort($caps);

    $archive_by_login[$archive_user->user_login] = [
        'id' => (int) $archive_user->ID,
        'login' => $archive_user->user_login,
        'email' => $archive_user->user_email,
        'display_name' => $archive_user->display_name,
        'nicename' => $archive_user->user_nicename,
        'status' => (int) $archive_user->user_status,
        'roles' => $roles,
        'caps' => $caps,
        'password_hash' => $archive_user->user_pass,
    ];
}

$plan = [
    'site' => home_url(),
    'create' => [],
    'update' => [],
    'stale_users' => [],
];

$source_logins = [];

foreach ($source['users'] as $source_user) {
    if (!is_array($source_user) || empty($source_user['login'])) {
        continue;
    }

    $login = (string) $source_user['login'];
    $source_logins[$login] = true;
    $archive_user = $archive_by_login[$login] ?? null;

    if ($archive_user === null) {
        $plan['create'][] = [
            'login' => $login,
            'email' => (string) ($source_user['email'] ?? ''),
            'display_name' => (string) ($source_user['display_name'] ?? ''),
            'roles' => array_values($source_user['roles'] ?? []),
        ];
        continue;
    }

    $changes = [];

    foreach (['email', 'display_name', 'nicename', 'status'] as $field) {
        $source_value = $source_user[$field] ?? null;
        $archive_value = $archive_user[$field] ?? null;
        if ($source_value !== $archive_value) {
            $changes[$field] = [
                'from' => $archive_value,
                'to' => $source_value,
            ];
        }
    }

    $source_roles = array_values($source_user['roles'] ?? []);
    sort($source_roles);
    $archive_roles = array_values($archive_user['roles'] ?? []);
    sort($archive_roles);
    if ($source_roles !== $archive_roles) {
        $changes['roles'] = [
            'from' => $archive_roles,
            'to' => $source_roles,
        ];
    }

    $source_caps = $source_user['caps'] ?? [];
    if (!is_array($source_caps)) {
        $source_caps = [];
    }
    ksort($source_caps);
    $archive_caps = $archive_user['caps'] ?? [];
    if (!is_array($archive_caps)) {
        $archive_caps = [];
    }
    ksort($archive_caps);
    if ($source_caps !== $archive_caps) {
        $changes['caps'] = [
            'from_hash' => hash('sha256', wp_json_encode($archive_caps)),
            'to_hash' => hash('sha256', wp_json_encode($source_caps)),
        ];
    }

    $source_password_hash = (string) ($source_user['password_hash'] ?? '');
    $archive_password_hash = (string) ($archive_user['password_hash'] ?? '');
    if ($source_password_hash !== $archive_password_hash) {
        $changes['password_hash'] = [
            'from_digest' => hash('sha256', $archive_password_hash),
            'to_digest' => hash('sha256', $source_password_hash),
        ];
    }

    if ($changes !== []) {
        $plan['update'][] = [
            'login' => $login,
            'archive_user_id' => (int) $archive_user['id'],
            'changes' => $changes,
        ];
    }
}

foreach ($archive_by_login as $login => $archive_user) {
    if (isset($source_logins[$login])) {
        continue;
    }

    $plan['stale_users'][] = [
        'login' => $login,
        'archive_user_id' => (int) $archive_user['id'],
        'note' => 'reported_only_no_delete',
    ];
}

echo wp_json_encode($plan, JSON_UNESCAPED_SLASHES);
