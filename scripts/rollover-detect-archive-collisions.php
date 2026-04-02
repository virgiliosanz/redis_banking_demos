<?php

$slugs_csv = getenv('ROLLOVER_SLUGS_CSV');
if ($slugs_csv === false) {
    fwrite(STDERR, "Missing ROLLOVER_SLUGS_CSV\n");
    exit(1);
}

$slugs = array_values(array_filter(array_map('sanitize_title', explode(',', $slugs_csv))));
$collisions = [];

foreach ($slugs as $slug) {
    $post = get_page_by_path($slug, OBJECT, 'post');
    if (!$post instanceof WP_Post) {
        continue;
    }

    $collisions[] = [
        'id' => (int) $post->ID,
        'slug' => $slug,
        'date' => $post->post_date,
        'url' => get_permalink($post),
    ];
}

$result = [
    'site' => home_url(),
    'collision_count' => count($collisions),
    'collisions' => $collisions,
];

echo wp_json_encode($result, JSON_UNESCAPED_SLASHES);
