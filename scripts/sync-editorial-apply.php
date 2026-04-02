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

$results = [
    'site' => home_url(),
    'created' => [],
    'updated' => [],
    'stale_users' => [],
];

foreach ($source['users'] as $source_user) {
    if (!is_array($source_user) || empty($source_user['login'])) {
        continue;
    }

    $login = (string) $source_user['login'];
    if (isset($excluded_lookup[$login])) {
        continue;
    }

    $existing = get_user_by('login', $login);
    $email = (string) ($source_user['email'] ?? '');
    $display_name = (string) ($source_user['display_name'] ?? '');
    $nicename = (string) ($source_user['nicename'] ?? $login);
    $status = (int) ($source_user['status'] ?? 0);
    $roles = $source_user['roles'] ?? [];
    if (!is_array($roles)) {
        $roles = [];
    }
    $caps = $source_user['caps'] ?? [];
    if (!is_array($caps)) {
        $caps = [];
    }
    $password_hash = (string) ($source_user['password_hash'] ?? '');

    if (!$existing instanceof WP_User) {
        $user_id = wp_insert_user([
            'user_login' => $login,
            'user_email' => $email,
            'display_name' => $display_name,
            'user_nicename' => $nicename,
            'user_status' => $status,
            'role' => $roles !== [] ? $roles[0] : '',
            'user_pass' => wp_generate_password(48, true, true),
        ]);

        if (is_wp_error($user_id)) {
            fwrite(STDERR, "Could not create user $login: " . $user_id->get_error_message() . "\n");
            exit(1);
        }

        $existing = get_user_by('id', $user_id);
        $results['created'][] = $login;
    } else {
        $user_id = (int) $existing->ID;
    }

    wp_update_user([
        'ID' => $user_id,
        'user_email' => $email,
        'display_name' => $display_name,
        'user_nicename' => $nicename,
        'user_status' => $status,
    ]);

    $user_obj = new WP_User($user_id);
    foreach ($user_obj->roles as $role) {
        $user_obj->remove_role($role);
    }
    foreach ($caps as $capability => $grant) {
        if (!$grant) {
            continue;
        }
        $user_obj->add_cap((string) $capability);
    }
    foreach ($roles as $role) {
        $user_obj->add_role((string) $role);
    }

    if ($password_hash !== '') {
        $GLOBALS['wpdb']->update(
            $GLOBALS['wpdb']->users,
            ['user_pass' => $password_hash],
            ['ID' => $user_id],
            ['%s'],
            ['%d']
        );
        clean_user_cache($user_id);
    }

    $results['updated'][] = $login;
}

$archive_users = get_users([
    'orderby' => 'login',
    'order' => 'ASC',
]);
$source_logins = [];
foreach ($source['users'] as $source_user) {
    if (!empty($source_user['login'])) {
        $source_logins[(string) $source_user['login']] = true;
    }
}
foreach ($archive_users as $archive_user) {
    if (isset($excluded_lookup[$archive_user->user_login])) {
        continue;
    }
    if (isset($source_logins[$archive_user->user_login])) {
        continue;
    }
    $results['stale_users'][] = $archive_user->user_login;
}

echo wp_json_encode($results, JSON_UNESCAPED_SLASHES);
