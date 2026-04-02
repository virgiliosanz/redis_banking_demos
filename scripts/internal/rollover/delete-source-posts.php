<?php

$snapshot_file = getenv('ROLLOVER_SNAPSHOT_FILE');
if ($snapshot_file === false || $snapshot_file === '' || !file_exists($snapshot_file)) {
    fwrite(STDERR, "Missing or invalid ROLLOVER_SNAPSHOT_FILE\n");
    exit(1);
}

$snapshot = json_decode((string) file_get_contents($snapshot_file), true);
if (!is_array($snapshot) || !isset($snapshot['posts']) || !is_array($snapshot['posts'])) {
    fwrite(STDERR, "Invalid snapshot file\n");
    exit(1);
}

$deleted = [];

foreach ($snapshot['posts'] as $post_data) {
    if (!is_array($post_data) || empty($post_data['source_post_id'])) {
        continue;
    }

    $post_id = (int) $post_data['source_post_id'];
    if (get_post($post_id) instanceof WP_Post) {
        wp_delete_post($post_id, true);
        $deleted[] = $post_id;
    }
}

$result = [
    'site' => home_url(),
    'deleted_post_ids' => $deleted,
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
