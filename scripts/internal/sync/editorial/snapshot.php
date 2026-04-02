<?php

$exclude_csv = getenv('SYNC_EXCLUDE_USER_LOGINS');
$excluded = array_filter(array_map('trim', explode(',', $exclude_csv !== false ? $exclude_csv : 'n9liveadmin,n9archiveadmin')));
$excluded_lookup = array_fill_keys($excluded, true);

$users = get_users([
    'orderby' => 'login',
    'order' => 'ASC',
]);

$result_users = [];

foreach ($users as $user) {
    if (isset($excluded_lookup[$user->user_login])) {
        continue;
    }

    $user_obj = get_userdata($user->ID);
    $roles = $user_obj instanceof WP_User ? array_values($user_obj->roles) : [];
    sort($roles);

    $caps = get_user_meta($user->ID, $GLOBALS['wpdb']->prefix . 'capabilities', true);
    if (!is_array($caps)) {
        $caps = [];
    }
    ksort($caps);

    $result_users[] = [
        'login' => $user->user_login,
        'email' => $user->user_email,
        'display_name' => $user->display_name,
        'nicename' => $user->user_nicename,
        'status' => (int) $user->user_status,
        'roles' => $roles,
        'caps_hash' => hash('sha256', wp_json_encode($caps)),
        'password_hash_digest' => hash('sha256', $user->user_pass),
    ];
}

$result = [
    'site' => home_url(),
    'excluded_logins' => array_values($excluded),
    'users' => $result_users,
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
